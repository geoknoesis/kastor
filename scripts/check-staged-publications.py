"""Validate staged POM metadata and source/documentation artifacts before consumer testing."""
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile

repository = Path(sys.argv[1] if len(sys.argv) > 1 else "build/release-repository")
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
# Artifact naming scheme for the com.geoknoesis.kastor group (plugin markers use their own group).
ARTIFACT_ID = re.compile(r"rdf-[a-z0-9-]+|kastor-bom|kastor-gen-[a-z0-9-]+|onto-quality(-[a-z0-9-]+)?")
poms = list(repository.rglob("*.pom"))
if not poms:
    raise SystemExit("No staged publications found")
for pom in poms:
    root = ET.parse(pom).getroot()
    for field in ("groupId", "artifactId", "version", "name", "description", "url",
                  "licenses/license/name", "licenses/license/url", "scm/url",
                  "developers/developer/id", "developers/developer/name"):
        query = "/".join(f"m:{part}" for part in field.split("/"))
        if not root.findtext(query, "", ns).strip():
            raise SystemExit(f"{pom}: missing or empty {field}")
    if (root.findtext("m:groupId", "", ns).strip() == "com.geoknoesis.kastor"
            and not ARTIFACT_ID.fullmatch(root.findtext("m:artifactId", "", ns).strip())):
        raise SystemExit(f"{pom}: artifactId breaks the naming scheme "
                         "(rdf-*, kastor-bom, kastor-gen-*, onto-quality[-*])")
    if root.findtext("m:packaging", "jar", ns) == "pom":
        continue
    for classifier in ("binary", "sources", "javadoc"):
        jar = pom.with_name(pom.stem + ("" if classifier == "binary" else f"-{classifier}") + ".jar")
        if not jar.is_file():
            raise SystemExit(f"Missing {jar}")
        with ZipFile(jar) as archive:
            suffix = {"binary": ".class", "javadoc": ".html", "sources": ".kt"}[classifier]
            if not any(not entry.is_dir() and entry.filename.endswith(suffix) and
                       entry.file_size > 0 for entry in archive.infolist()):
                raise SystemExit(f"Empty {classifier} content in {jar}")
            if archive.testzip() is not None:
                raise SystemExit(f"Corrupt {classifier} content in {jar}")
print(f"Validated metadata and required artifacts for {len(poms)} staged publications")
