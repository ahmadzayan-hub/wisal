"""Run one isolated Android acceptance phase. Never install, wipe or reboot a device."""
import argparse
import datetime
import hashlib
import json
from pathlib import Path
import re
import subprocess

PACKAGE = "com.wisal.app.acceptance"
TEST_PACKAGE = PACKAGE + ".test"
TEST_CLASS = "com.wifeassistant.DeviceAcceptanceTest"


class DeviceBlocked(RuntimeError):
    pass


def digest(path):
    with Path(path).open("rb") as handle:
        return hashlib.file_digest(handle, "sha256").hexdigest()


def instrumentation_passed(output, returncode):
    return (returncode == 0 and re.search(r"^OK \(1 test\)\s*$", output, re.M) is not None
            and re.search(r"^INSTRUMENTATION_CODE: -1\s*$", output, re.M) is not None
            and not any(marker in output for marker in (
                "FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed", "shortMsg=",
                "INSTRUMENTATION_STATUS_CODE: -1", "INSTRUMENTATION_STATUS_CODE: -2")))


def verify_installed(adb, package, expected):
    path = adb("shell", "pm", "path", package).strip()
    match = re.fullmatch(r"package:(/data/app/[A-Za-z0-9_./=+~\-]+/base\.apk)", path)
    if not match:
        raise DeviceBlocked("Expected a single installed acceptance APK; no install or data reset was attempted")
    remote_digest = adb("shell", "sha256sum", match.group(1)).split()
    if not remote_digest or remote_digest[0].lower() != expected:
        raise DeviceBlocked("Installed acceptance APK checksum differs from the supplied artifact")


def collect(args, execute=subprocess.run):
    report = {"schema": 1, "utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
              "phase": args.phase, "sourceCommit": args.source_commit,
              "deviceReference": hashlib.sha256(args.serial.encode()).hexdigest(),
              "package": PACKAGE, "status": "BLOCKED", "releaseGate": "HOLD",
              "mayClaimE2ee": False, "scope": "isolated synthetic single-device instrumentation"}
    if args.phase == "keyLoss" and not args.allow_test_key_loss:
        report["reason"] = "keyLoss requires --allow-test-key-loss; only the acceptance UID is affected"
        return report, 2

    def adb(*command, timeout=30):
        completed = execute([args.adb, "-s", args.serial, *command], capture_output=True,
                            text=True, timeout=timeout, check=False)
        if completed.returncode:
            raise DeviceBlocked("ADB command failed; raw device output was not included")
        return completed.stdout

    try:
        report["apkSha256"] = digest(args.apk)
        report["testApkSha256"] = digest(args.test_apk)
        if adb("get-state").strip() != "device":
            raise DeviceBlocked("Selected Android device is not ready")
        report["model"] = adb("shell", "getprop", "ro.product.model").strip()[:100]
        report["androidRelease"] = adb("shell", "getprop", "ro.build.version.release").strip()[:30]
        report["apiLevel"] = adb("shell", "getprop", "ro.build.version.sdk").strip()[:10]
        emulator = adb("shell", "getprop", "ro.kernel.qemu").strip()
        report["deviceKind"] = "EMULATOR" if emulator == "1" else "UNVERIFIED_PHYSICAL"
        verify_installed(adb, PACKAGE, report["apkSha256"])
        verify_installed(adb, TEST_PACKAGE, report["testApkSha256"])
        adb("shell", "am", "force-stop", PACKAGE)
        completed = execute([args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-r",
                             "-e", "class", TEST_CLASS, "-e", "phase", args.phase,
                             TEST_PACKAGE + "/androidx.test.runner.AndroidJUnitRunner"],
                            capture_output=True, text=True, timeout=180, check=False)
        output = completed.stdout + completed.stderr
        report["instrumentationOutputSha256"] = hashlib.sha256(output.encode()).hexdigest()
        # ADB commonly returns zero even when instrumentation failed or ran zero tests.
        report["status"] = "PASS" if instrumentation_passed(output, completed.returncode) else "FAIL"
        report["reason"] = "One instrumentation test completed" if report["status"] == "PASS" else "Instrumentation failed or did not confirm exactly one test"
        return report, 0 if report["status"] == "PASS" else 1
    except (OSError, subprocess.TimeoutExpired, DeviceBlocked):
        report["reason"] = "Device/artifact prerequisite failed or timed out; no acceptance result claimed"
        return report, 2


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--serial", required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--test-apk", type=Path, required=True)
    parser.add_argument("--source-commit", required=True)
    parser.add_argument("--phase", required=True, choices=["prepare", "resume", "verify", "keyLoss"])
    parser.add_argument("--allow-test-key-loss", action="store_true")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not re.fullmatch(r"[a-fA-F0-9]{40}", args.source_commit):
        parser.error("source-commit must be a full commit SHA")
    if args.output.exists():
        parser.error("use a new evidence path; existing reports are never overwritten")
    report, code = collect(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    with args.output.open("x", encoding="utf-8") as handle:
        json.dump(report, handle, ensure_ascii=False, indent=2)
        handle.write("\n")
    print(report["status"] + ": " + report["reason"])
    raise SystemExit(code)


if __name__ == "__main__":
    main()
