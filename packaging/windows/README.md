# HyperRocket Windows Installer

This folder packages HyperRocket as a **native Windows installer** (`.exe`) with a
standard installation wizard: welcome screen, license agreement, install-location
chooser, Start-menu / desktop-shortcut options, an install progress bar, and a
finish page. Once installed, HyperRocket behaves like any other Windows program —
launch it from the Start menu or desktop, and uninstall it from
*Settings → Apps*.

The installer is produced by the JDK's [`jpackage`](https://docs.oracle.com/en/java/javase/17/docs/specs/man/jpackage.html)
tool, which bundles a private Java runtime so end users **do not need Java
installed**.

## Installing (for end users)

Download `HyperRocket-<version>.exe` from the
[Releases page](https://github.com/anayshah17/HyperRocket/releases) and run it.

Because the installer is **not code-signed**, Windows SmartScreen will warn you
the first time you run it:

1. Double-click the `.exe`.
2. If you see **"Windows protected your PC"**, click **More info → Run anyway**.
3. Follow the wizard.

Each release also ships a `.exe.sha256` file so you can verify the download:

```powershell
Get-FileHash .\HyperRocket-<version>.exe -Algorithm SHA256
```

The printed hash should match the contents of the `.sha256` file.

> **Smart App Control (SAC):** Some newer Windows 11 PCs enable Smart App
> Control, a stricter layer that may silently block unsigned apps with **no
> "Run anyway" option**. The only reliable fix is to **code-sign** the installer
> (e.g. with [Azure Trusted Signing](https://learn.microsoft.com/azure/trusted-signing/),
> ~$10/month). The CI workflow can be extended with a signing step once a
> certificate is available.

## Build it yourself (locally)

**Requirements**

| Tool | How to get it |
|------|---------------|
| JDK 17 (with `jpackage`) | https://adoptium.net (Temurin 17) |
| WiX Toolset v3.x | `winget install WiXToolset.WiXToolset` |

**Build**

From the repository root:

```bat
packaging\windows\build-installer.bat 1.0.0
```

The script builds the distributable jar (`gradlew dist`) if needed, then runs
`jpackage`. The finished installer is written to:

```
dist-installer\HyperRocket-1.0.0.exe
```

> `dist-installer/` is git-ignored — the installer is ~100 MB and is published as a
> GitHub Release asset rather than committed to the repository.

## Automatic builds (GitHub Actions)

The workflow [`.github/workflows/windows-installer.yml`](../../.github/workflows/windows-installer.yml)
builds the installer in CI and **attaches it to a GitHub Release**:

* **On a version tag** — push a tag like `v1.0.0` and the workflow builds the
  installer and uploads it to the matching Release automatically:

  ```bash
  git tag v1.0.0
  git push origin v1.0.0
  ```

* **Manually** — go to the repo's *Actions → Windows Installer → Run workflow*,
  enter a version, and download the installer from the run's artifacts.

## What the installer does

* Installs HyperRocket + a bundled Java 17 runtime under the chosen directory
  (default `C:\Program Files\HyperRocket`, or per-user if selected in the wizard).
* Creates Start-menu and (optionally) desktop shortcuts using the HyperRocket icon.
* Registers an uninstaller in *Apps & features*.
