import com.android.apksig.ApkVerifier
import java.io.File
import java.security.MessageDigest
import java.security.cert.X509Certificate

private fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
    .digest(cert.encoded)
    .joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "usage: VerifyApkSignatures <apk> [apk...]" }
    for (name in args) {
        val file = File(name)
        val result = ApkVerifier.Builder(file)
            .setMinCheckedPlatformVersion(23)
            .setMaxCheckedPlatformVersion(34)
            .build()
            .verify()
        println("APK: $name")
        println("verified=${result.isVerified} v1=${result.isVerifiedUsingV1Scheme} v2=${result.isVerifiedUsingV2Scheme} v3=${result.isVerifiedUsingV3Scheme} v4=${result.isVerifiedUsingV4Scheme}")
        result.signerCertificates.forEach { println("certificate-sha256=${fingerprint(it)}") }
        result.errors.forEach { println("error=$it") }
        result.warnings.forEach { println("warning=$it") }
        check(result.isVerified) { "Signature verification failed for $name" }
        check(result.isVerifiedUsingV1Scheme) { "Android 6 v1 verification failed for $name" }
        check(!result.containsErrors()) { "Signature errors reported for $name" }
    }
}
