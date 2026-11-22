"""Recompress existing deflated entries, align and sign the distributable APK."""
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
from zipfile import ZipFile

def certificate(signer, apk):
    output = subprocess.check_output([signer, "verify", "--print-certs", apk], text=True)
    return re.search(r"certificate SHA-256 digest: ([0-9a-fA-F]+)", output).group(1)

def main():
    source, output, build_tools, keyfile = map(Path, sys.argv[1:])
    signer = build_tools / "apksigner"
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=output.parent) as tmp:
        unsigned = Path(tmp) / "unsigned.apk"
        aligned = Path(tmp) / "aligned.apk"
        signed = Path(tmp) / "signed.apk"
        with ZipFile(source) as original, ZipFile(unsigned, "w") as compact:
            for item in original.infolist():
                if re.fullmatch(r"META-INF/(MANIFEST\.MF|[^/]+\.(SF|RSA|DSA|EC))", item.filename, re.I):
                    continue
                compact.writestr(item, original.read(item), compresslevel=9)
        subprocess.run([build_tools / "zipalign", "-P", "16", "-f", "4", unsigned, aligned], check=True)
        subprocess.run([signer, "sign", "--ks", keyfile, "--ks-key-alias", os.environ["APK_ALIAS"],
                        "--ks-pass", "env:APK_STORE_PASS", "--key-pass", "env:APK_KEY_PASS",
                        "--out", signed, aligned], check=True)
        if certificate(signer, source) != certificate(signer, signed):
            raise RuntimeError("APK signer changed")
        subprocess.run([build_tools / "zipalign", "-c", "-P", "16", "4", signed], check=True)
        signed.replace(output)

if __name__ == "__main__":
    main()
