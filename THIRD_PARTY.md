# Third-party components

- MariaDB Connector/J 3.5.6, LGPL-2.1-or-later. Original binary is retained at `libs/mariadb-java-client-3.5.6.jar`. Unmodified library classes are included in the mod JAR so Forge can load the driver without a separate mod. License files included by upstream are retained. Source: https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.6 ; replacement/rebuild: replace the library in `libs`, then rebuild with Gradle.
- The Gradle local repository contains the existing verified ForgeGradle/Minecraft build toolchain, with each artifact under its original license; it is not bundled into the release mod.
- `references/` contains TAB and Curtain solely for isolated compatibility validation; they are not bundled into the mod.
- Portable MariaDB under `tools/` is solely an isolated local test runtime, not registered as a Windows service and not distributed in the mod.
- React and React DOM 19.2.0, MIT, https://github.com/facebook/react/tree/v19.2.0 . Bundled in the embedded web application; original licenses retained at `/web/licenses/react.txt` and `/web/licenses/react-dom.txt` in the JAR.
- Phosphor Icons React 2.1.10, MIT, https://github.com/phosphor-icons/react . Bundled SVG components; license retained at `/web/licenses/phosphor-icons-react.txt`.
- Inter variable font, distributed by `@fontsource-variable/inter` 5.3.0, SIL Open Font License 1.1, https://github.com/rsms/inter . Local WOFF2 files are embedded; license retained at `/web/licenses/inter-ofl.txt`.
- Vite and its React plugin are build tools, not needed on the Minecraft server. Exact frontend dependency versions are locked in `web-console/package-lock.json`.
- Linear's design-refresh article and reference captures under `docs/web-reference/` were used for design discussion. Linear's trademark, application code and source artwork are not included in the embedded web UI.
- The eighteen 8x8 default head thumbnails under `/web/default-heads/` are face/hat crops of the locally installed Minecraft 1.20.1 default player textures (Mojang/Microsoft). Original texture paths and the UUID mapping are documented in `tools/prepare_default_heads.py`; no complete skin textures or Minecraft game code are bundled as web assets.

## Project license scope

AGPL-3.0-only applies to PixelMC-authored code, not to third-party dependencies or assets. Their original licenses and notices remain in force. Minecraft default-skin thumbnails are Mojang/Microsoft artwork and are not relicensed as PixelMC code.

The installed frontend dependency inventory and original license texts are retained under `web-console/public/licenses`; this inventory includes build tools as well as runtime packages.
