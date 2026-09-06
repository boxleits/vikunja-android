# Dev container (Fedora Atomic + Podman + native VS Code)

This container pins **JDK 17** and installs the **Android SDK** (platform-tools,
`platforms;android-35`, `build-tools;35.0.0`), so `./gradlew :app:assembleDebug`
works without layering a JDK or Android Studio onto the host.

It exists mainly because Fedora Atomic hosts are awkward for Android builds:
the base image is immutable, and a too-new system JDK (25, 27-ea) breaks
Gradle 8.14 / AGP 8.6 / Kotlin 2.0 with a version-compatibility error.

## Host setup

Assumes Silverblue / Kinoite / Bazzite / Bluefin with **VS Code installed
natively** (layered RPM, not Flatpak — the Flatpak build is sandboxed and
can't drive the host's Podman).

1. **VS Code**, if not already layered:

   ```bash
   sudo rpm-ostree install code   # after enabling Microsoft's vscode repo
   systemctl reboot
   ```

2. **Podman** ships in the Fedora Atomic base image — nothing to install.
   Confirm with `podman info`.

3. **Dev Containers extension**:

   ```bash
   code --install-extension ms-vscode-remote.remote-containers
   ```

4. **Point the extension at Podman** (VS Code → Settings → JSON, i.e.
   `~/.config/Code/User/settings.json`):

   ```json
   {
     "dev.containers.dockerPath": "podman",
     "dev.containers.dockerComposePath": "podman-compose"
   }
   ```

5. Open the repo in VS Code → **Reopen in Container**.

## Why the Podman-specific `runArgs`

`devcontainer.json` passes two flags that matter on Fedora:

| Flag | Why |
|---|---|
| `--userns=keep-id:uid=1000,gid=1000` | Rootless Podman remaps UIDs, so without this every file the container writes into your working tree lands with the wrong owner. This maps your host user onto the image's `vscode` user (UID 1000). |
| `--security-opt=label=disable` | SELinux is enforcing on Atomic and denies container access to the bind-mounted workspace. Disabling labelling for this container is the simplest fix; the stricter alternative is a `:Z`-labelled `workspaceMount`. |

**If your host UID isn't 1000** (check with `id -u`), change the `keep-id`
values to match — otherwise file ownership will still be off.

**If you use Docker rather than Podman**, delete both flags: Docker rejects
`--userns=keep-id`.

## Verifying it works

Inside the container:

```bash
./gradlew :core:test          # pure-Kotlin logic, fast
./gradlew :app:assembleDebug  # full Android build
```

## Running the app on a device

The container builds APKs; it doesn't run them.

- **Physical device:** easiest is to pair from the *host* — `adb` on the host,
  or copy `app/build/outputs/apk/debug/app-debug.apk` off and install it.
  USB passthrough into rootless Podman is possible but fiddly.
- **Emulator:** needs KVM passthrough — add `"--device=/dev/kvm"` to `runArgs`
  and make sure your user can access it (`ls -l /dev/kvm`). Hardware
  acceleration will not work without it.

## Bumping SDK versions

The `ARG`s at the top of the `Dockerfile` (`ANDROID_COMPILE_SDK`,
`ANDROID_BUILD_TOOLS`) must stay in sync with `app/build.gradle.kts`.
`ANDROID_CMDLINE_TOOLS_VERSION` is Google's build number for the
command-line tools zip — current value is at
<https://developer.android.com/studio#command-line-tools-only>.
