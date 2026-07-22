# Third-Party Notices

This file records third-party software and service terms relevant to Light RSS. It is an engineering inventory, not legal advice. Anyone distributing an APK or another binary should review the licenses and current service terms for that exact build.

## Inventory scope

The inventory was checked on 2026-07-22 against the resolved Gradle `:tool:releaseRuntimeClasspath` for Light RSS 1.0.0. Multiplatform metadata, Android/JVM variants, and modules from the same upstream project are grouped below. Build-only plugins, code generators, test libraries, and the Android platform itself are outside this runtime inventory.

To reproduce the dependency graph:

```sh
./gradlew :tool:dependencies --configuration releaseRuntimeClasspath
```

The release optimizer may remove unused bytecode, so this is intentionally a conservative inventory of the resolved release classpath rather than a claim that every class is present in every optimized APK. Recheck it whenever dependencies change.

## Source incorporated into this repository

| Component | Version or revision | License | Source |
| --- | --- | --- | --- |
| Light SDK client, shared, and UI modules | SDK 0.0.12; upstream checkout `d502bca78037d9606f76cda35596828c8af390dd` | MIT | [lightphone/light-sdk](https://github.com/lightphone/light-sdk) |
| Light Phone III Keyboard UI | Maven artifact 0.0.11 | MIT | [lightphone/light-keyboard](https://github.com/lightphone/light-keyboard) |

Copyright (c) 2026 The Light Phone. The applicable MIT permission and warranty text is reproduced in the repository [LICENSE](LICENSE). Light RSS is an unofficial community project and is not affiliated with, sponsored by, or endorsed by The Light Phone.

The keyboard 0.0.11 POM and AAR do not contain license metadata or a bundled license file. Its MIT classification was verified against the public upstream repository on the audit date; a distributor should recheck the provenance of the exact packaged artifact.

## Resolved runtime components

| Component or Maven group | Resolved version(s) | License or terms | Upstream |
| --- | --- | --- | --- |
| AndroidX Compose (`androidx.compose.*`) | BOM 2026.03.01; Compose 1.10.6; Material 3 1.4.0 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx) |
| AndroidX Activity, Annotation, AppCompat, Arch Core, Autofill, Collection, Concurrent, Core, CursorAdapter, CustomView, DocumentFile, DrawerLayout, DynamicAnimation, Emoji2, ExifInterface, Fragment, Graphics, Interpolator, Legacy, Loader, LocalBroadcastManager, NavigationEvent, Print, ProfileInstaller, ResourceInspection, SavedState, Startup, Tracing, Transition, VectorDrawable, VersionedParcelable, ViewPager, and Window | Activity 1.13.0; Annotation 1.10.0 and 1.4.1; AppCompat 1.6.1; Arch Core 2.2.0; Autofill 1.0.0; Collection 1.5.0; Concurrent 1.1.0; Core 1.18.0, SplashScreen 1.0.1, and ViewTree 1.0.0; CursorAdapter 1.0.0; CustomView 1.0.0; DocumentFile 1.0.0; DrawerLayout 1.0.0; DynamicAnimation 1.0.0; Emoji2 1.4.0; ExifInterface 1.3.2; Fragment 1.3.6; Graphics Path 1.0.1; Interpolator 1.0.0; Legacy 1.0.0; Loader 1.0.0; LocalBroadcastManager 1.0.0; NavigationEvent 1.0.0; Print 1.0.0; ProfileInstaller 1.4.0; ResourceInspection 1.0.1; SavedState 1.4.0; Startup 1.2.0; Tracing 1.2.0; Transition 1.6.0; VectorDrawable 1.1.0; VersionedParcelable 1.1.1; ViewPager 1.0.0; Window 1.5.0 | Apache-2.0 | [AndroidX](https://github.com/androidx/androidx) |
| AndroidX CameraX (`androidx.camera.*`) | 1.5.0 | Apache-2.0; bundled libyuv portions are BSD-3-Clause | [CameraX](https://developer.android.com/jetpack/androidx/releases/camera), [libyuv](https://chromium.googlesource.com/libyuv/libyuv/) |
| AndroidX DataStore, Lifecycle, Room, SQLite, and WorkManager | DataStore 1.2.1; Lifecycle 2.10.0; Room 2.7.0; SQLite 2.5.0; WorkManager 2.10.0 | Apache-2.0; DataStore's external Protocol Buffers code is BSD-3-Clause | [AndroidX](https://github.com/androidx/androidx) |
| Kotlin standard library | 2.3.20 | Apache-2.0 | [Kotlin](https://github.com/JetBrains/kotlin) |
| Kotlin coroutines, serialization, and I/O | Coroutines 1.10.2; Serialization 1.9.0; I/O 0.8.2 | Apache-2.0 | [Kotlinx](https://github.com/Kotlin) |
| JetBrains annotations | 23.0.0 | Apache-2.0 | [JetBrains annotations](https://github.com/JetBrains/java-annotations) |
| Ktor client and serialization modules (`io.ktor:*`) | 3.4.2 | Apache-2.0 | [Ktor](https://github.com/ktorio/ktor) |
| OkHttp and Okio | OkHttp 5.3.2; Okio 3.16.4 | Apache-2.0 | [OkHttp](https://github.com/square/okhttp), [Okio](https://github.com/square/okio) |
| UnifiedPush Android Connector | 3.3.2 | Apache-2.0 | [UnifiedPush/android-connector](https://github.com/UnifiedPush/android-connector) |
| Google open-source support libraries: Android Data Transport, Firebase components/encoders/annotations, AutoValue annotations, Gson, Tink, Error Prone annotations, Guava ListenableFuture, FindBugs JSR-305, and `javax.inject` | Data Transport API 2.2.1, runtime 2.2.6, backend CCT 2.3.3; Firebase annotations 16.0.0, components 16.1.0, encoders 16.1.0/17.1.0; AutoValue annotations 1.6.3; Gson 2.13.2; Tink 1.20.0; Error Prone annotations 2.41.0; ListenableFuture 1.0; JSR-305 3.0.2; `javax.inject` 1 | Apache-2.0 | [Google Open Source](https://opensource.google/), [javax.inject](https://github.com/javax-inject/javax-inject) |
| Protocol Buffers Java | 4.33.0 | BSD-3-Clause | [protocolbuffers/protobuf](https://github.com/protocolbuffers/protobuf) |
| JSpecify | 1.0.0 | Apache-2.0 | [jspecify/jspecify](https://github.com/jspecify/jspecify) |
| SLF4J API | 2.0.17 | MIT | [SLF4J](https://www.slf4j.org/) |
| ML Kit barcode/vision components (`com.google.mlkit:*`) | Barcode Scanning 17.3.0; Barcode Scanning Common 17.0.0; Common 18.11.0; Vision Common 17.3.0; Vision Interfaces 16.3.0 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) | [ML Kit](https://developers.google.com/ml-kit) |
| Google Play services ML Kit Barcode Scanning | 18.3.1 | [ML Kit Terms of Service](https://developers.google.com/ml-kit/terms) | [ML Kit](https://developers.google.com/ml-kit) |
| Google Play services Base, Basement, and Tasks; Google ODML Image | Base 18.5.0; Basement 18.4.0; Tasks 18.2.0; ODML Image 1.0.0-beta1 | [Android Software Development Kit License Agreement](https://developer.android.com/studio/terms) | [Google Android SDK terms](https://developer.android.com/studio/terms) |

Google ML Kit and Google Play services components are pulled in transitively by the Light SDK UI's QR-scanner support. Light RSS does not call that scanner. Their manifests and initialization code can nevertheless be merged into an unoptimized APK. The ML Kit terms state that the APIs may contact Google for updates and may send performance and utilization metrics. Binary distributors should verify the final manifest and optimized APK, ensure the privacy disclosure matches the shipped artifact, and review the current Google terms before release.

## License texts and required notices

### Apache License 2.0

The Apache License 2.0 applies to the components identified as Apache-2.0 above.

<details>
<summary>Apache License, Version 2.0</summary>

```text
                                 Apache License
                           Version 2.0, January 2004
                        http://www.apache.org/licenses/

   TERMS AND CONDITIONS FOR USE, REPRODUCTION, AND DISTRIBUTION

   1. Definitions.

      "License" shall mean the terms and conditions for use, reproduction,
      and distribution as defined by Sections 1 through 9 of this document.

      "Licensor" shall mean the copyright owner or entity authorized by
      the copyright owner that is granting the License.

      "Legal Entity" shall mean the union of the acting entity and all
      other entities that control, are controlled by, or are under common
      control with that entity. For the purposes of this definition,
      "control" means (i) the power, direct or indirect, to cause the
      direction or management of such entity, whether by contract or
      otherwise, or (ii) ownership of fifty percent (50%) or more of the
      outstanding shares, or (iii) beneficial ownership of such entity.

      "You" (or "Your") shall mean an individual or Legal Entity
      exercising permissions granted by this License.

      "Source" form shall mean the preferred form for making modifications,
      including but not limited to software source code, documentation
      source, and configuration files.

      "Object" form shall mean any form resulting from mechanical
      transformation or translation of a Source form, including but
      not limited to compiled object code, generated documentation,
      and conversions to other media types.

      "Work" shall mean the work of authorship, whether in Source or
      Object form, made available under the License, as indicated by a
      copyright notice that is included in or attached to the work
      (an example is provided in the Appendix below).

      "Derivative Works" shall mean any work, whether in Source or Object
      form, that is based on (or derived from) the Work and for which the
      editorial revisions, annotations, elaborations, or other modifications
      represent, as a whole, an original work of authorship. For the purposes
      of this License, Derivative Works shall not include works that remain
      separable from, or merely link (or bind by name) to the interfaces of,
      the Work and Derivative Works thereof.

      "Contribution" shall mean any work of authorship, including
      the original version of the Work and any modifications or additions
      to that Work or Derivative Works thereof, that is intentionally
      submitted to Licensor for inclusion in the Work by the copyright owner
      or by an individual or Legal Entity authorized to submit on behalf of
      the copyright owner. For the purposes of this definition, "submitted"
      means any form of electronic, verbal, or written communication sent
      to the Licensor or its representatives, including but not limited to
      communication on electronic mailing lists, source code control systems,
      and issue tracking systems that are managed by, or on behalf of, the
      Licensor for the purpose of discussing and improving the Work, but
      excluding communication that is conspicuously marked or otherwise
      designated in writing by the copyright owner as "Not a Contribution."

      "Contributor" shall mean Licensor and any individual or Legal Entity
      on behalf of whom a Contribution has been received by Licensor and
      subsequently incorporated within the Work.

   2. Grant of Copyright License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      copyright license to reproduce, prepare Derivative Works of,
      publicly display, publicly perform, sublicense, and distribute the
      Work and such Derivative Works in Source or Object form.

   3. Grant of Patent License. Subject to the terms and conditions of
      this License, each Contributor hereby grants to You a perpetual,
      worldwide, non-exclusive, no-charge, royalty-free, irrevocable
      (except as stated in this section) patent license to make, have made,
      use, offer to sell, sell, import, and otherwise transfer the Work,
      where such license applies only to those patent claims licensable
      by such Contributor that are necessarily infringed by their
      Contribution(s) alone or by combination of their Contribution(s)
      with the Work to which such Contribution(s) was submitted. If You
      institute patent litigation against any entity (including a
      cross-claim or counterclaim in a lawsuit) alleging that the Work
      or a Contribution incorporated within the Work constitutes direct
      or contributory patent infringement, then any patent licenses
      granted to You under this License for that Work shall terminate
      as of the date such litigation is filed.

   4. Redistribution. You may reproduce and distribute copies of the
      Work or Derivative Works thereof in any medium, with or without
      modifications, and in Source or Object form, provided that You
      meet the following conditions:

      (a) You must give any other recipients of the Work or
          Derivative Works a copy of this License; and

      (b) You must cause any modified files to carry prominent notices
          stating that You changed the files; and

      (c) You must retain, in the Source form of any Derivative Works
          that You distribute, all copyright, patent, trademark, and
          attribution notices from the Source form of the Work,
          excluding those notices that do not pertain to any part of
          the Derivative Works; and

      (d) If the Work includes a "NOTICE" text file as part of its
          distribution, then any Derivative Works that You distribute must
          include a readable copy of the attribution notices contained
          within such NOTICE file, excluding those notices that do not
          pertain to any part of the Derivative Works, in at least one
          of the following places: within a NOTICE text file distributed
          as part of the Derivative Works; within the Source form or
          documentation, if provided along with the Derivative Works; or,
          within a display generated by the Derivative Works, if and
          wherever such third-party notices normally appear. The contents
          of the NOTICE file are for informational purposes only and
          do not modify the License. You may add Your own attribution
          notices within Derivative Works that You distribute, alongside
          or as an addendum to the NOTICE text from the Work, provided
          that such additional attribution notices cannot be construed
          as modifying the License.

      You may add Your own copyright statement to Your modifications and
      may provide additional or different license terms and conditions
      for use, reproduction, or distribution of Your modifications, or
      for any such Derivative Works as a whole, provided Your use,
      reproduction, and distribution of the Work otherwise complies with
      the conditions stated in this License.

   5. Submission of Contributions. Unless You explicitly state otherwise,
      any Contribution intentionally submitted for inclusion in the Work
      by You to the Licensor shall be under the terms and conditions of
      this License, without any additional terms or conditions.
      Notwithstanding the above, nothing herein shall supersede or modify
      the terms of any separate license agreement you may have executed
      with Licensor regarding such Contributions.

   6. Trademarks. This License does not grant permission to use the trade
      names, trademarks, service marks, or product names of the Licensor,
      except as required for reasonable and customary use in describing the
      origin of the Work and reproducing the content of the NOTICE file.

   7. Disclaimer of Warranty. Unless required by applicable law or
      agreed to in writing, Licensor provides the Work (and each
      Contributor provides its Contributions) on an "AS IS" BASIS,
      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
      implied, including, without limitation, any warranties or conditions
      of TITLE, NON-INFRINGEMENT, MERCHANTABILITY, or FITNESS FOR A
      PARTICULAR PURPOSE. You are solely responsible for determining the
      appropriateness of using or redistributing the Work and assume any
      risks associated with Your exercise of permissions under this License.

   8. Limitation of Liability. In no event and under no legal theory,
      whether in tort (including negligence), contract, or otherwise,
      unless required by applicable law (such as deliberate and grossly
      negligent acts) or agreed to in writing, shall any Contributor be
      liable to You for damages, including any direct, indirect, special,
      incidental, or consequential damages of any character arising as a
      result of this License or out of the use or inability to use the
      Work (including but not limited to damages for loss of goodwill,
      work stoppage, computer failure or malfunction, or any and all
      other commercial damages or losses), even if such Contributor
      has been advised of the possibility of such damages.

   9. Accepting Warranty or Additional Liability. While redistributing
      the Work or Derivative Works thereof, You may choose to offer,
      and charge a fee for, acceptance of support, warranty, indemnity,
      or other liability obligations and/or rights consistent with this
      License. However, in accepting such obligations, You may act only
      on Your own behalf and on Your sole responsibility, not on behalf
      of any other Contributor, and only if You agree to indemnify,
      defend, and hold each Contributor harmless for any liability
      incurred by, or claims asserted against, such Contributor by reason
      of your accepting any such warranty or additional liability.

   END OF TERMS AND CONDITIONS
```

</details>

### SLF4J

Copyright (c) 2004-2022 QOS.ch Sarl (Switzerland). All rights reserved.

Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

### Protocol Buffers

Copyright 2008 Google Inc. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
- Neither the name of Google Inc. nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

Code generated by the Protocol Buffer compiler is owned by the owner of the input file used when generating it. That generated code is not standalone and requires a support library to be linked with it. The support library is covered by the license above.

### libyuv

Copyright 2011 The LibYuv Project Authors. All rights reserved.

Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

- Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
- Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer in the documentation and/or other materials provided with the distribution.
- Neither the name of Google nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

## Feed content

NASA, BBC World, and Hacker News are starter subscriptions only. Their names and feed content belong to their respective owners and are fetched directly from their public feeds. They do not sponsor or endorse Light RSS.

## Trademarks

Light Phone, LightOS, Light Phone III, Android, Google, ML Kit, and other product names may be trademarks of their respective owners. Their use here identifies compatibility, dependencies, or feed sources only.
