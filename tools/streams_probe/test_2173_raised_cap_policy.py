"""JDK21 behavior test for the pure Streams 2.17.3 redundant-cap admission rule."""
from __future__ import annotations

import argparse
import subprocess
from pathlib import Path

from test_2162_bed_hook import java_tool

ROOT = Path(__file__).resolve().parents[2]
POLICY = ROOT / "common/src/main/java/com/github/xandergos/terraindiffusionmc/world/river/NaturalRiverRaisedCapPolicy.java"

HARNESS = r'''
import com.github.xandergos.terraindiffusionmc.world.river.NaturalRiverRaisedCapPolicy;
public class RaisedCapPolicyHarness {
  static void check(boolean actual, boolean expected, String name) {
    if (actual != expected) throw new AssertionError(name + ": expected " + expected + " got " + actual);
  }
  static boolean decide(int profile, boolean corridor, boolean channel, int ground, int water,
                        int cap, boolean air, boolean soil) {
    return NaturalRiverRaisedCapPolicy.suppress(profile, corridor, channel, ground, water, cap, air, soil);
  }
  public static void main(String[] args) {
    check(decide(14,true,false,71,71,72,true,true), true, "baseline redundant cap");
    check(decide(14,true,false,70,71,72,true,true), false, "needed containment below water");
    check(decide(14,true,true,71,71,72,true,true), false, "channel");
    check(decide(14,false,false,71,71,72,true,true), false, "outside corridor");
    check(decide(13,true,false,71,71,72,true,true), false, "older profile");
    check(decide(14,true,false,71,71,72,false,true), false, "existing solid or fluid");
    check(decide(14,true,false,71,71,72,true,false), false, "non-soil cap");
    check(decide(14,true,false,71,71,71,true,true), false, "natural ground cap");
    check(decide(14,true,false,74,71,75,false,true), false, "high terrain existing write");
    System.out.println("RaisedCapPolicyHarness: PASS");
  }
}
'''


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path)
    parser.add_argument("--out", type=Path, default=ROOT / "build/streams-2173-review/raised-cap-policy-test")
    args = parser.parse_args()
    javac = java_tool("javac", args.java_home)
    java = java_tool("java", args.java_home)
    assert javac and java, "JDK21 javac/java are required"
    source = args.out / "RaisedCapPolicyHarness.java"
    args.out.mkdir(parents=True, exist_ok=True)
    source.write_text(HARNESS, encoding="utf-8")
    flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    subprocess.run([javac, "--release", "21", "-d", str(args.out), str(POLICY), str(source)],
                   cwd=ROOT, check=True, creationflags=flags)
    result = subprocess.run([java, "-ea", "-cp", str(args.out), "RaisedCapPolicyHarness"],
                            cwd=ROOT, text=True, capture_output=True, check=True,
                            creationflags=flags)
    assert "RaisedCapPolicyHarness: PASS" in result.stdout, result.stdout + result.stderr
    print(result.stdout.strip())


if __name__ == "__main__":
    main()
