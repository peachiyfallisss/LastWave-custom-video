import copy
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("publisher", Path(__file__).with_name("publish-managed-update.py"))
publisher = importlib.util.module_from_spec(spec)
spec.loader.exec_module(publisher)


def release(channel, code, cert="b" * 64):
    tag = f"managed-{channel}-{code}"
    return {"dataSchemaVersion": 13, "versionCode": code, "versionName": f"4.2.2-{channel}.{code}", "minSdk": 29, "sizeBytes": 123,
            "sha256": "a" * 64, "certificateSha256": cert,
            "apkUrl": f"https://github.com/{publisher.REPO}/releases/download/{tag}/LastWave-Updates.apk",
            "releaseUrl": f"https://github.com/{publisher.REPO}/releases/tag/{tag}"}


class PublishingPolicyTest(unittest.TestCase):
    def test_numbered_and_sdk_scoped_signer_output_excludes_source_stamp(self):
        pin = "a" * 64
        self.assertEqual({pin}, publisher.signer_fingerprints(f"Signer #1 certificate SHA-256 digest: {pin}\n"))
        output = (f"Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: {pin}\n"
                  f"Signer (minSdkVersion=29, maxSdkVersion=32) certificate SHA-256 digest: {pin}\n"
                  f"Source Stamp Signer certificate SHA-256 digest: {'b' * 64}\n")
        self.assertEqual({pin}, publisher.signer_fingerprints(output))
        self.assertEqual({pin, "c" * 64}, publisher.signer_fingerprints(output + f"Signer #2 certificate SHA-256 digest: {'c' * 64}\n"))

    def test_bootstrap_and_automatic_channel_promotion(self):
        test = publisher.advance_feed(None, release("test", 100), "test", True)
        stable = publisher.advance_feed(test, release("stable", 101), "stable", True)
        self.assertEqual("stable", stable["activeChannel"])
        self.assertEqual({"test", "stable"}, set(stable["releases"]))
        self.assertEqual("test", test["activeChannel"], "Do not mutate a prior feed")

    def test_test_channel_does_not_force_stable_only_users(self):
        stable = publisher.advance_feed(None, release("stable", 100), "stable", True)
        test = publisher.advance_feed(stable, release("test", 101), "test", False)
        self.assertEqual("stable", test["activeChannel"])
        self.assertEqual(100, test["releases"]["stable"]["versionCode"])

    def test_cannot_activate_old_stable_apk_as_a_rollback(self):
        stable = publisher.advance_feed(None, release("stable", 100), "stable", True)
        test = publisher.advance_feed(stable, release("test", 101), "test", True)
        for code in (99, 100, 101):
            with self.assertRaises(AssertionError):
                publisher.advance_feed(test, release("stable", code), "stable", True)

    def test_cannot_promote_a_database_downgrade(self):
        test = release("test", 100)
        test["dataSchemaVersion"] = 14
        feed = publisher.advance_feed(None, test, "test", True)
        with self.assertRaises(AssertionError):
            publisher.advance_feed(feed, release("stable", 101), "stable", True)

    def test_permanent_key_cannot_be_silently_rotated(self):
        feed = publisher.advance_feed(None, release("test", 1), "test", True)
        with self.assertRaises(AssertionError):
            publisher.advance_feed(feed, release("stable", 2, "c" * 64), "stable", True)

    def test_idempotent_publish_and_untrusted_manifest_rejection(self):
        item = release("test", 1)
        feed = publisher.advance_feed(None, item, "test", True)
        self.assertEqual(feed, publisher.advance_feed(feed, item, "test", True))
        for field, value in (("apkUrl", "https://evil.test/app.apk"), ("versionCode", 1.5), ("sizeBytes", 0),
                             ("certificateSha256", ""), ("minSdk", 1)):
            invalid = copy.deepcopy(item)
            invalid[field] = value
            with self.assertRaises(AssertionError):
                publisher.advance_feed(None, invalid, "test", True)
        with self.assertRaises(AssertionError):
            publisher.advance_feed(None, item, "test", False)


if __name__ == "__main__":
    unittest.main()
