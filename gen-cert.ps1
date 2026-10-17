# Generate server.jks: a self-signed CA certificate.
# Contains two entries:
#   alias "server" : PrivateKeyEntry (private key + cert, used as identity)
#   alias "ca"     : TrustedCertEntry (same cert, used as trust anchor)
# Password is 123456 for both store and key.

$ErrorActionPreference = "Stop"

$jks = "server.jks"
$pass = "123456"
$cert = "server.cer"

if (Test-Path $jks) {
    Write-Host "Removing existing $jks ..."
    Remove-Item $jks -Force
}
if (Test-Path $cert) {
    Remove-Item $cert -Force
}

$keytool = if ($env:JAVA_HOME) { "$env:JAVA_HOME\bin\keytool.exe" } else { "keytool" }

Write-Host "[1/3] Generate self-signed cert (alias=server)..."
& $keytool -genkeypair `
    -alias server `
    -keyalg RSA -keysize 2048 `
    -validity 3650 `
    -keystore $jks `
    -storetype JKS `
    -storepass $pass `
    -keypass $pass `
    -dname "CN=localhost, OU=CoAP, O=CoapLearn, L=Test, ST=Test, C=CN" `
    -ext "SAN=dns:localhost,ip:127.0.0.1" `
    -ext "EKU=serverAuth,clientAuth" `
    -ext "bc:c"

Write-Host "[2/3] Export certificate to $cert ..."
& $keytool -exportcert `
    -alias server `
    -keystore $jks `
    -storepass $pass `
    -file $cert

Write-Host "[3/3] Import as trustedCertEntry (alias=ca) into same jks ..."
& $keytool -importcert `
    -alias ca `
    -file $cert `
    -keystore $jks `
    -storepass $pass `
    -noprompt

Remove-Item $cert -Force

Write-Host ""
Write-Host "Done. $jks entries:"
& $keytool -list -keystore $jks -storepass $pass
