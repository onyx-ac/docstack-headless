# Merges this module's own Kotlin/JS Zipline manifest (engine/build/zipline/Production/)
# with the separately esbuild+zipline-cli-compiled real pouchdb-core +
# @docstack/pouchdb-adapter-native bundle (engine/js-bundle/ziplineOut/) into
# engine/combined/ - one more `modules` entry depending on the Kotlin app module, keeping
# the Kotlin module's own mainModuleId/mainFunction. See docstack-headless/SPIKE-NOTES.md
# "manifest-merge technique" for why this can't just be two separate ZiplineLoader calls.
import json
import shutil
import os

combined = "combined"
os.makedirs(combined, exist_ok=True)

engine_manifest = json.load(open("build/zipline/Production/manifest.zipline.json"))
bundle_manifest = json.load(open("js-bundle/ziplineOut/manifest.zipline.json"))

for mod_id, mod in engine_manifest["modules"].items():
    shutil.copy(f"build/zipline/Production/{mod['url']}", f"{combined}/{mod['url']}")

bundle_mod = bundle_manifest["modules"]["./bundle.js"]
shutil.copy(f"js-bundle/ziplineOut/{bundle_mod['url']}", f"{combined}/{bundle_mod['url']}")

modules = dict(engine_manifest["modules"])
modules["./bundle.js"] = {**bundle_mod, "dependsOnIds": ["./docstack-headless-engine.js"]}

merged = {
    "unsigned": {"signatures": {}, "freshAtEpochMs": None, "baseUrl": None},
    "modules": modules,
    "mainModuleId": engine_manifest["mainModuleId"],
    "mainFunction": engine_manifest["mainFunction"],
    "version": None,
    "metadata": {},
}
json.dump(merged, open(f"{combined}/manifest.zipline.json", "w"))
print("wrote", f"{combined}/manifest.zipline.json")
