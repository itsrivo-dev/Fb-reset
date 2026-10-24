import com.android.apksig.ApkSigner
import com.android.apksig.ApkVerifier
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Base64

private fun pemBody(path: File): ByteArray {
    val lines = path.readLines().filterNot { it.startsWith("-----") || it.isBlank() }
    return Base64.getDecoder().decode(lines.joinToString(""))
}

private fun fingerprint(cert: X509Certificate): String = MessageDigest.getInstance("SHA-256")
    .digest(cert.encoded)
    .joinToString("") { "%02x".format(it) }

fun main(args: Array<String>) {
    require(args.size == 4) { "usage: SignAndVerifyApk <unsigned.apk> <signed.apk> <key.pem> <cert.pem>" }
    val input = File(args[0]).absoluteFile
    val output = File(args[1]).absoluteFile
    require(input.isFile) { "Unsigned input is missing" }
    require(input.canonicalPath != output.canonicalPath) { "Input and output must be different files" }
    val keyBytes = pemBody(File(args[2]))
    val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyBytes))
    val certificate = CertificateFactory.getInstance("X.509").generateCertificate(File(args[3]).inputStream()) as X509Certificate
    val config = ApkSigner.SignerConfig.Builder("W8REPAIR", privateKey, listOf(certificate)).build()
    output.parentFile?.mkdirs()
    if (output.exists()) output.delete()
    try {
        ApkSigner.Builder(listOf(config))
            .setInputApk(input)
            .setOutputApk(output)
            .setMinSdkVersion(21)
            .setV1SigningEnabled(true)
            .setV2SigningEnabled(true)
            .setV3SigningEnabled(true)
            .setV4SigningEnabled(false)
            .setOtherSignersSignaturesPreserved(false)
            .setDebuggableApkPermitted(false)
            .setCreatedBy("User-authorized W8PremiumYT repair")
            .build()
            .sign()
        val result = ApkVerifier.Builder(output)
            .setMinCheckedPlatformVersion(23)
            .setMaxCheckedPlatformVersion(34)
            .build()
            .verify()
        check(result.isVerified) { "APK signature verification failed: ${result.errors}" }
        check(result.isVerifiedUsingV1Scheme) { "Android 6 v1 signature is missing" }
        check(result.isVerifiedUsingV2Scheme) { "v2 signature is missing" }
        check(result.isVerifiedUsingV3Scheme) { "v3 signature is missing" }
        check(!result.containsErrors()) { "Signature verifier reported errors: ${result.errors}" }
        check(result.signerCertificates.isNotEmpty()) { "No signer certificate" }
        check(result.signerCertificates[0].encoded.contentEquals(certificate.encoded)) { "Unexpected signing certificate" }
        println("Signed APK verification: passed for checked platform versions 23 through 34")
        println("v1=${result.isVerifiedUsingV1Scheme} v2=${result.isVerifiedUsingV2Scheme} v3=${result.isVerifiedUsingV3Scheme} v4=${result.isVerifiedUsingV4Scheme}")
        println("Certificate SHA-256: ${fingerprint(certificate)}")
        result.warnings.forEach { println("Verifier warning: $it") }
    } catch (error: Throwable) {
        output.delete()
        throw error
    }
}
