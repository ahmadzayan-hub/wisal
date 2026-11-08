import argparse
from pathlib import Path
import subprocess
import tempfile
import unittest
from CollectDeviceEvidence import collect, digest, instrumentation_passed, verify_installed, DeviceBlocked


SUCCESS = "OK (1 test)\nINSTRUMENTATION_CODE: -1\n"


class DeviceEvidenceTest(unittest.TestCase):
    def testInstrumentationMustActuallyRunAndFinish(self):
        self.assertTrue(instrumentation_passed(SUCCESS, 0))
        for output, code in [("OK (0 tests)\nINSTRUMENTATION_CODE: -1", 0),
                             (SUCCESS, 1), ("INSTRUMENTATION_CODE: -1", 0),
                             ("OK (1 test)", 0), (SUCCESS + "Process crashed", 0),
                             (SUCCESS + "FAILURES!!!", 0), (SUCCESS + "shortMsg=error", 0), ("NOT " + SUCCESS, 0),
                             (SUCCESS + "INSTRUMENTATION_STATUS_CODE: -2", 0)]:
            self.assertFalse(instrumentation_passed(output, code))

    def testPackagePathCannotInjectShellCommands(self):
        for path in ["package:/data/app/x/base.apk;echo secret", "package:/data/user/0/com.wisal.app/base.apk",
                     "package:/data/app/x/base.apk\npackage:/data/app/x/split.apk"]:
            with self.assertRaises(DeviceBlocked):
                verify_installed(lambda *args: path, "com.wisal.app.acceptance", "a" * 64)

    def testFullLifecycleChecksArtifactsAndKeepsGateClosed(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / "Synthetic.apk"; apk.write_bytes(b"synthetic test artifact")
            commands = []
            def execute(command, **kwargs):
                commands.append(command)
                if command[-1] == "get-state": output = "device\n"
                elif "getprop" in command: output = "1" if command[-1] == "ro.kernel.qemu" else "synthetic"
                elif "path" in command: output = "package:/data/app/~~synthetic/com.wisal.app.acceptance/base.apk\n"
                elif "sha256sum" in command: output = digest(apk) + "  /data/app/synthetic/base.apk\n"
                elif "instrument" in command: output = SUCCESS
                else: output = ""
                return subprocess.CompletedProcess(command, 0, output, "")
            args = argparse.Namespace(phase="prepare", source_commit="a" * 40, serial="synthetic-serial",
                                      allow_test_key_loss=False, adb="adb", apk=apk, test_apk=apk)
            report, code = collect(args, execute)
            self.assertEqual(0, code); self.assertEqual("PASS", report["status"])
            self.assertEqual("HOLD", report["releaseGate"]); self.assertFalse(report["mayClaimE2ee"])
            self.assertEqual("EMULATOR", report["deviceKind"])
            self.assertNotIn("synthetic-serial", str(report))
            self.assertFalse(any("uninstall" in c or "reboot" in c or "clear" in c for c in commands))
            self.assertTrue(all("com.wisal.app" not in c for c in commands))

    def testLostKeyPhaseRequiresExplicitIsolatedTestOptIn(self):
        args = argparse.Namespace(phase="keyLoss", source_commit="a" * 40, serial="synthetic",
                                  allow_test_key_loss=False)
        report, code = collect(args, lambda *args, **kwargs: self.fail("ADB should not run"))
        self.assertEqual(2, code); self.assertEqual("BLOCKED", report["status"])

    def testMissingArtifactNeverClaimsPass(self):
        args = argparse.Namespace(phase="prepare", source_commit="a" * 40, serial="synthetic",
                                  allow_test_key_loss=False, apk=Path("MissingSynthetic.apk"))
        report, code = collect(args)
        self.assertEqual(2, code); self.assertEqual("BLOCKED", report["status"])

    def testMismatchedInstalledArtifactBlocksExecution(self):
        calls = iter(["package:/data/app/~~synthetic/app/base.apk", "b" * 64 + " base.apk"])
        with self.assertRaises(DeviceBlocked):
            verify_installed(lambda *args: next(calls), "com.wisal.app.acceptance", "a" * 64)


if __name__ == "__main__":
    unittest.main()
