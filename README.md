LWJGL 3 - Vulkan Template Generator
===================================

The LWJGL 3 Vulkan template generator is a tool that parses the Vulkan API
specification and generates LWJGL 3 Generator templates. The goal is to
fully automate the process of updating the LWJGL bindings of Vulkan and all
its extensions.

Instructions
------------

- Clone [Vulkan-Docs](https://github.com/KhronosGroup/Vulkan-Docs.git).
- Apply the [LWJGL-fixes](https://github.com/LWJGL/lwjgl3-vulkangen/blob/master/LWJGL-fixes.patch) patch. (e.g. `patch -p1 < LWJGL-fixes.patch` under `<Vulkan-Docs>/`)
- Build the reference pages. All extensions should be included (e.g. `./makeAllExts -j 8 manhtmlpages` under `<Vulkan-Docs>/doc/specs/vulkan/`).
- Clone [LWJGL 3](https://github.com/LWJGL/lwjgl3.git).
- Set `path.vulkan-docs` and `path.lwjgl3` in `pom.xml` to the root of the cloned Vulkan-Docs and LWJGL 3 repositories respectively.
- Run `mvn compile exec:java`.