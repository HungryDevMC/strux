# Third-party notices

Strux is licensed under the [Business Source License 1.1](LICENSE). The
distributed Minecraft plugin (`StructuralIntegrity-<version>.jar`) bundles the
following third-party components, which keep their own licenses. Their license
terms are reproduced or referenced below to satisfy attribution.

The plugin's build shades and relocates these libraries (under
`dev.gesp.structural.minecraft.shaded.*`) so they cannot clash with another
plugin's copy on the same server; relocation does not change their licenses.

---

## Jackson (jackson-databind, jackson-core, jackson-annotations)

- **Project:** FasterXML Jackson — <https://github.com/FasterXML/jackson>
- **Version:** 2.17.0 (databind; core and annotations ride in transitively)
- **Copyright:** © FasterXML, LLC
- **License:** Apache License, Version 2.0 — <https://www.apache.org/licenses/LICENSE-2.0>

Used by Strux only for JSON persistence in the Minecraft adapter.

## fastutil

- **Project:** fastutil — <https://fastutil.di.unimi.it/> / <https://github.com/vigna/fastutil>
- **Copyright:** © Sebastiano Vigna
- **License:** Apache License, Version 2.0 — <https://www.apache.org/licenses/LICENSE-2.0>

Pulled in transitively from `core` for primitive collections on the hot path.

### FastDoubleParser (bundled within fastutil)

- **Project:** FastDoubleParser — <https://github.com/wrandelshofer/FastDoubleParser>
- **Copyright:** © Werner Randelshofer
- **License:** MIT License

fastutil embeds FastDoubleParser; its `META-INF/FastDoubleParser-LICENSE` and
`-NOTICE` ship inside the fastutil artifact.

---

## A note on the Apache License 2.0

Both Jackson and fastutil are distributed under the Apache License, Version 2.0.
The full text is available at <https://www.apache.org/licenses/LICENSE-2.0>. Per
Section 4 of that license, the attributions above accompany the distributed jar.
Strux makes no changes to these libraries other than package relocation.
