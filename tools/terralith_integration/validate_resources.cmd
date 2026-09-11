@echo off
set PYTHONUTF8=1
python -B "%~dp0validate_resources.py" %*
if errorlevel 1 exit /b %errorlevel%
python -B "%~dp0validate_resources.py" --common-before-terralith %*
