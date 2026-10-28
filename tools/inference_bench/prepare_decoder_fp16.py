"""Create a fresh, inspectable FP16 candidate for the pinned decoder ONNX model.

This is an offline conversion utility.  It neither installs packages nor runs an
ONNX Runtime session.  The resulting directory contains only a candidate model
and a conversion receipt; it never changes the pinned source model.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import sys
from collections import Counter
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[2]
DEFAULT_SOURCE = ROOT / "build/chunk-profile/td-decoder-cache-256-b/terrain-diffusion-models/decoder_model.onnx"
DEFAULT_OUTPUT = ROOT / "build/inference-bench/decoder-precision/fp16-einsum-fp32-candidate"
MIN_POSITIVE = 1.0e-7
MAX_FINITE = 1.0e4
# This decoder materializes weights through Cast-to-FLOAT nodes.  Do not block
# Einsum preemptively: its input can be converted with the rest of the graph.
EXTRA_OP_BLOCK_LIST: tuple[str, ...] = ()


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def value_info(value: Any) -> dict[str, Any]:
    tensor = value.type.tensor_type
    return {
        "name": value.name,
        "elem_type": int(tensor.elem_type),
        "shape": [dimension.dim_value if dimension.HasField("dim_value") else dimension.dim_param
                  for dimension in tensor.shape.dim],
    }


def graph_summary(model: Any, onnx: Any) -> dict[str, Any]:
    return {
        "ir_version": model.ir_version,
        "opsets": [{"domain": entry.domain, "version": entry.version} for entry in model.opset_import],
        "nodes": len(model.graph.node),
        "operators": dict(sorted(Counter(node.op_type for node in model.graph.node).items())),
        "inputs": [value_info(value) for value in model.graph.input],
        "outputs": [value_info(value) for value in model.graph.output],
        "initializer_data_types": dict(sorted(
            Counter(onnx.TensorProto.DataType.Name(initializer.data_type)
                    for initializer in model.graph.initializer).items())),
        "initializers": len(model.graph.initializer),
    }


def floating_initializer_stats(model: Any, numpy_helper: Any) -> dict[str, int]:
    import numpy as np

    result = Counter()
    for initializer in model.graph.initializer:
        values = numpy_helper.to_array(initializer)
        if values.dtype.kind != "f":
            continue
        flattened = values.reshape(-1)
        result["floating_tensors"] += 1
        result["floating_values"] += int(flattened.size)
        finite = np.isfinite(flattened)
        result["non_finite_values"] += int(flattened.size - np.count_nonzero(finite))
        absolute = np.abs(flattened)
        result["above_max_finite_values"] += int(np.count_nonzero(finite & (absolute > MAX_FINITE)))
        result["below_min_positive_nonzero_values"] += int(np.count_nonzero(
            finite & (absolute > 0.0) & (absolute < MIN_POSITIVE)))
    return dict(sorted(result.items()))


def conversion_delta(source: Any, candidate: Any, onnx: Any) -> dict[str, Any]:
    source_by_name = {initializer.name: initializer for initializer in source.graph.initializer}
    candidate_by_name = {initializer.name: initializer for initializer in candidate.graph.initializer}
    converted_tensors = 0
    converted_values = 0
    for name, before in source_by_name.items():
        after = candidate_by_name.get(name)
        if after is None:
            continue
        if before.data_type == onnx.TensorProto.FLOAT and after.data_type == onnx.TensorProto.FLOAT16:
            converted_tensors += 1
            value_count = 1
            for dimension in before.dims:
                value_count *= dimension
            converted_values += value_count
    casts = Counter()
    for node in candidate.graph.node:
        if node.op_type != "Cast":
            continue
        target = next((attribute.i for attribute in node.attribute if attribute.name == "to"), None)
        casts[str(target)] += 1
    return {
        "float32_to_float16_initializers": converted_tensors,
        "float32_to_float16_values": converted_values,
        "source_node_count": len(source.graph.node),
        "candidate_node_count": len(candidate.graph.node),
        "candidate_cast_nodes_by_target_tensorproto": dict(sorted(casts.items())),
    }


def rewrite_original_float_casts_to_fp16(model: Any, original_cast_names: set[str], onnx: Any) -> list[str]:
    """Retarget source weight-materialization casts after graph-wide conversion.

    The converter changes the consumers to FP16 but deliberately retains source
    Cast(to=FLOAT) attributes.  This model uses those casts to materialize every
    convolution/linear weight, so retaining them makes the converted Conv graph
    type-invalid.  Restrict the rewrite to names that were source casts; casts
    inserted by `keep_io_types` remain intact.
    """
    rewritten: list[str] = []
    for node in model.graph.node:
        if node.name not in original_cast_names or node.op_type != "Cast":
            continue
        target = next((attribute for attribute in node.attribute if attribute.name == "to"), None)
        if target is None or target.i != onnx.TensorProto.FLOAT:
            continue
        target.i = onnx.TensorProto.FLOAT16
        rewritten.append(node.name)
    return rewritten


def check(path: Path, onnx: Any) -> dict[str, Any]:
    try:
        onnx.checker.check_model(str(path), full_check=True)
        return {"status": "passed", "full_check": True}
    except TypeError:
        onnx.checker.check_model(str(path))
        return {"status": "passed", "full_check": False, "note": "installed onnx lacks full_check"}


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source", type=Path, default=DEFAULT_SOURCE)
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUTPUT,
                        help="must not exist; candidate decoder_model.onnx and receipt.json are written there")
    parser.add_argument("--disable-shape-infer", action="store_true",
                        help="ask the converter not to rewrite this already shape-annotated graph")
    parser.add_argument("--rewrite-original-float-casts-to-fp16", action="store_true",
                        help="retarget source Cast(to=FLOAT) weight-materialization nodes after conversion")
    parser.add_argument("--block-einsum", action="store_true",
                        help="keep the Fourier Einsum in FLOAT when its kept FLOAT input requires it")
    parser.add_argument("--block-reductions", action="store_true",
                        help="keep ReduceL2/ReduceMean/ReduceSum in FLOAT for normalization accumulations")
    args = parser.parse_args()
    source = args.source.resolve()
    output = args.output_dir.resolve()
    if not source.is_file():
        raise SystemExit(f"source model is missing: {source}")
    if output.exists():
        raise SystemExit(f"refusing to overwrite existing candidate directory: {output}")
    build_root = (ROOT / "build").resolve()
    if not output.is_relative_to(build_root):
        raise SystemExit(f"candidate output must stay below workspace build/: {output}")
    try:
        import onnx
        from onnx import numpy_helper
        from onnxconverter_common import float16
        import onnxconverter_common
    except ModuleNotFoundError as error:
        raise SystemExit(
            "missing conversion dependency: " + str(error)
            + "; create a workspace venv with onnx and onnxconverter-common, then rerun"
        ) from error

    source_model = onnx.load(str(source), load_external_data=False)
    source_check = check(source, onnx)
    source_summary = graph_summary(source_model, onnx)
    source_stats = floating_initializer_stats(source_model, numpy_helper)
    original_float_cast_names = {
        node.name for node in source_model.graph.node
        if node.op_type == "Cast"
        and any(attribute.name == "to" and attribute.i == onnx.TensorProto.FLOAT for attribute in node.attribute)
    }
    output.mkdir(parents=True)
    candidate_path = output / "decoder_model.onnx"
    receipt_path = output / "receipt.json"
    log_path = output / "conversion.stdout.log"
    log_lines: list[str] = []
    candidate_model: Any | None = None
    candidate_summary: dict[str, Any] | None = None
    candidate_stats: dict[str, int] | None = None
    candidate_check: dict[str, Any] | None = None
    try:
        # onnxconverter-common transforms its input protobuf in place.  Reloading
        # gives conversion an independent graph, so every source field below is
        # a real before-conversion value.
        extra_op_block_list = list(EXTRA_OP_BLOCK_LIST)
        if args.block_einsum:
            extra_op_block_list.append("Einsum")
        if args.block_reductions:
            # The decoder has no fused normalization operators.  These three
            # reduction families are the accumulation points in its explicit
            # weight/group-normalization subgraphs; the converter inserts the
            # required FP16<->FP32 boundary casts around each blocked node.
            extra_op_block_list.extend(("ReduceL2", "ReduceMean", "ReduceSum"))
        op_block_list = list(float16.DEFAULT_OP_BLOCK_LIST) + extra_op_block_list
        candidate_model = onnx.load_from_string(source_model.SerializeToString())
        candidate_model = float16.convert_float_to_float16(
            candidate_model, min_positive_val=MIN_POSITIVE, max_finite_val=MAX_FINITE,
            keep_io_types=True, disable_shape_infer=args.disable_shape_infer,
            op_block_list=op_block_list,
        )
        rewritten_cast_names: list[str] = []
        if args.rewrite_original_float_casts_to_fp16:
            rewritten_cast_names = rewrite_original_float_casts_to_fp16(
                candidate_model, original_float_cast_names, onnx)
        onnx.save(candidate_model, str(candidate_path))
        candidate_model = onnx.load(str(candidate_path), load_external_data=False)
        candidate_stats = floating_initializer_stats(candidate_model, numpy_helper)
        candidate_summary = graph_summary(candidate_model, onnx)
        if candidate_stats.get("non_finite_values", 0) != 0:
            raise RuntimeError("candidate contains non-finite floating initializer values")
        invariant_checks = {
            "ir_version_unchanged": source_summary["ir_version"] == candidate_summary["ir_version"],
            "opsets_unchanged": source_summary["opsets"] == candidate_summary["opsets"],
            "inputs_preserved": source_summary["inputs"] == candidate_summary["inputs"],
            "outputs_preserved": source_summary["outputs"] == candidate_summary["outputs"],
        }
        if not all(invariant_checks.values()):
            raise RuntimeError("candidate changed required model IR/opset/input/output metadata: "
                               + json.dumps(invariant_checks, sort_keys=True))
        candidate_check = check(candidate_path, onnx)
        receipt = {
            "status": "success",
            "kind": "decoder_fp16_candidate",
            "source": {"path": str(source), "sha256": sha256(source), "bytes": source.stat().st_size,
                       "graph": source_summary, "floating_initializer_stats": source_stats,
                       "checker": source_check},
            "candidate": {"path": str(candidate_path), "sha256": sha256(candidate_path),
                          "bytes": candidate_path.stat().st_size, "graph": candidate_summary,
                          "floating_initializer_stats": candidate_stats, "checker": candidate_check},
            "conversion": {
                "keep_io_types": True,
                "disable_shape_infer": args.disable_shape_infer,
                "rewrite_original_float_casts_to_fp16": args.rewrite_original_float_casts_to_fp16,
                "rewritten_original_float_casts": {
                    "count": len(rewritten_cast_names),
                    "names": rewritten_cast_names,
                },
                "min_positive_val": MIN_POSITIVE,
                "max_finite_val": MAX_FINITE,
                "default_op_block_list": list(float16.DEFAULT_OP_BLOCK_LIST),
                "extra_op_block_list": extra_op_block_list,
                "op_block_list": op_block_list,
                "node_block_list": [],
                "delta": conversion_delta(source_model, candidate_model, onnx),
                "invariant_checks": invariant_checks,
            },
            "packages": {"python": sys.version, "onnx": onnx.__version__,
                         "onnxconverter_common": onnxconverter_common.__version__},
        }
        receipt_path.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        log_lines.extend((f"candidate={candidate_path}", f"receipt={receipt_path}"))
    except BaseException as error:
        candidate_diagnostic: dict[str, Any] | None = None
        if candidate_model is not None and candidate_path.is_file():
            candidate_diagnostic = {
                "path": str(candidate_path),
                "sha256": sha256(candidate_path),
                "bytes": candidate_path.stat().st_size,
                "graph": candidate_summary,
                "floating_initializer_stats": candidate_stats,
                "checker": candidate_check or {"status": "failed", "full_check": True,
                                                  "error": str(error)},
                "delta": conversion_delta(source_model, candidate_model, onnx),
            }
        diagnostic = {
            "status": "failed",
            "source": {"path": str(source), "sha256": sha256(source), "bytes": source.stat().st_size,
                       "graph": source_summary, "floating_initializer_stats": source_stats,
                       "checker": source_check},
            "error": {"type": type(error).__name__, "message": str(error)},
            "packages": {"python": sys.version, "onnx": onnx.__version__,
                         "onnxconverter_common": onnxconverter_common.__version__},
        }
        if candidate_diagnostic is not None:
            diagnostic["candidate"] = candidate_diagnostic
        receipt_path.write_text(json.dumps(diagnostic, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        log_lines.extend((f"failed={type(error).__name__}: {error}", f"receipt={receipt_path}"))
        log_path.write_text("\n".join(log_lines) + "\n", encoding="utf-8")
        raise
    log_path.write_text("\n".join(log_lines) + "\n", encoding="utf-8")
    print("\n".join(log_lines))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
