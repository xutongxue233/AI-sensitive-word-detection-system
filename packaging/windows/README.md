# Windows Local Package

This directory builds a Windows unzip-and-run package for the video moderation system.
If you distribute source code, prefer the repository root `start-local.bat` /
`setup-local.bat` flow. This package is for users who should not install
Maven/Node/Python build tools themselves.

The generated package embeds:

- Spring Boot backend Jar
- Built React frontend inside the backend Jar
- ASR service files and optional `.venv`
- OCR service files and optional `.venv`
- FFmpeg binaries from `backend/tools/ffmpeg`
- Runtime scripts: `start.bat`, `stop.bat`, `status.bat`

The runtime scripts start ASR, OCR, and backend as hidden managed processes, so
the user does not get separate service console windows.

## Build Package

Run from the repository root or this directory:

```powershell
.\packaging\windows\build-local-package.ps1 -JdkHome D:\Environment\jdk21
```

For the closest unzip-and-run package, include both Java and Python runtimes:

```powershell
.\packaging\windows\build-local-package.ps1 `
  -JdkHome D:\Environment\jdk21 `
  -IncludeJdk:$true `
  -IncludePythonRuntime:$true
```

By default it:

- builds `frontend/dist`
- temporarily copies `frontend/dist` into backend static resources
- builds the backend Jar
- copies ASR/OCR `.venv` directories if present
- optionally copies a portable Java runtime and Python runtime
- creates `release\AI-sensitive-word-detection-system-local`
- creates `release\AI-sensitive-word-detection-system-local.zip`

For a faster structural test without copying large Python venvs or zipping:

```powershell
.\packaging\windows\build-local-package.ps1 -JdkHome D:\Environment\jdk21 -IncludePythonVenv:$false -NoZip
```

To include a portable JDK in the package:

```powershell
.\packaging\windows\build-local-package.ps1 -JdkHome D:\Environment\jdk21 -IncludeJdk:$true
```

If `-IncludeJdk` is not used, the target machine must have Java 21 available in `PATH`.

If `-IncludePythonRuntime` is not used, the target machine must have a compatible
Python installation at the path referenced by each copied `.venv\pyvenv.cfg`.
For distribution to another machine, use `-IncludePythonRuntime:$true`.

## Runtime Usage

Unzip the package and run:

```bat
start.bat
```

Open:

```text
http://127.0.0.1:8090/
```

Stop services:

```bat
stop.bat
```

Check status:

```bat
status.bat
```

## Configuration

Edit after unzip:

```text
config\local.env
```

Default mode is CPU-first for compatibility. GPU can be enabled in `local.env`,
but the packaged Python virtual environments must contain compatible CUDA builds.
