"""Validate staged POM metadata, source/documentation artifacts and CycloneDX SBOMs before consumer testing."""
import json
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
                  "licenses/license/name", "licenses/license/url", "scm/url", "scm/connection",
                  "scm/developerConnection", "issueManagement/url",
                  "developers/developer/id", "developers/developer/name", "developers/developer/url"):
        query = "/".join(f"m:{part}" for part in field.split("/"))
        if not root.findtext(query, "", ns).strip():
            raise SystemExit(f"{pom}: missing or empty {field}")
    if (root.findtext("m:groupId", "", ns).strip() == "com.geoknoesis.kastor"
            and not ARTIFACT_ID.fullmatch(root.findtext("m:artifactId", "", ns).strip())):
        raise SystemExit(f"{pom}: artifactId breaks the naming scheme "
                         "(rdf-*, kastor-bom, kastor-gen-*, onto-quality[-*])")
    # Every dependency must carry a version for consumers: a version supplied only by the internal build platform
    # is lost on publication and makes the artifact unresolvable outside this build.
    for dependency in root.findall("m:dependencies/m:dependency", ns):
        if not dependency.findtext("m:version", "", ns).strip():
            coordinates = f"{dependency.findtext('m:groupId', '', ns)}:{dependency.findtext('m:artifactId', '', ns)}"
            raise SystemExit(f"{pom}: dependency {coordinates} has no version")
    module = pom.with_suffix(".module")
    if module.is_file():
        for variant in json.loads(module.read_text(encoding="utf-8")).get("variants", []):
            for dependency in variant.get("dependencies", []):
                version = dependency.get("version") or {}
                if not any(version.get(key) for key in ("requires", "strictly", "prefers")):
                    raise SystemExit(f"{module}: dependency {dependency.get('group')}:{dependency.get('module')} "
                                     f"has no version in variant {variant.get('name')}")
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
    # CycloneDX SBOM of the module's runtime closure, in both encodings, describing this artifact.
    group, artifact = root.findtext("m:groupId", "", ns).strip(), root.findtext("m:artifactId", "", ns).strip()
    purl = f"pkg:maven/{group}/{artifact}@"
    json_sbom, xml_sbom = (pom.with_name(pom.stem + f"-cyclonedx.{ext}") for ext in ("json", "xml"))
    for sbom in (json_sbom, xml_sbom):
        if not sbom.is_file():
            raise SystemExit(f"Missing SBOM {sbom}")
    try:
        document = json.loads(json_sbom.read_text(encoding="utf-8"))
        component = document["metadata"]["component"]
    except (ValueError, KeyError, TypeError) as error:
        raise SystemExit(f"Invalid CycloneDX JSON {json_sbom}: {error}")
    if document.get("bomFormat") != "CycloneDX" or not str(component.get("purl", "")).startswith(purl):
        raise SystemExit(f"{json_sbom}: not a CycloneDX SBOM for {purl}")
    try:
        sbom_root = ET.parse(xml_sbom).getroot()
    except ET.ParseError as error:
        raise SystemExit(f"Invalid CycloneDX XML {xml_sbom}: {error}")
    xml_ns = sbom_root.tag[1:].split("}")[0] if sbom_root.tag.startswith("{") else ""
    xml_purl = sbom_root.findtext("c:metadata/c:component/c:purl", "", {"c": xml_ns}).strip()
    if not xml_ns.startswith("http://cyclonedx.org/schema/bom/") or not xml_purl.startswith(purl):
        raise SystemExit(f"{xml_sbom}: not a CycloneDX SBOM for {purl}")
print(f"Validated metadata, required artifacts and SBOMs for {len(poms)} staged publications")
