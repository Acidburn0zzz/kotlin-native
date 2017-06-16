/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.backend.konan

import com.intellij.openapi.project.Project
import org.jetbrains.kotlin.backend.konan.library.KonanLibraryReader
import org.jetbrains.kotlin.backend.konan.library.KonanLibrarySearchPathResolver
import org.jetbrains.kotlin.backend.konan.library.impl.KonanKotlinSpecifics
import org.jetbrains.kotlin.backend.konan.library.impl.SplitLibraryReader
import org.jetbrains.kotlin.backend.konan.util.File
import org.jetbrains.kotlin.backend.konan.util.profile
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.descriptors.impl.ModuleDescriptorImpl

class KonanConfig(val project: Project, val configuration: CompilerConfiguration) {

    val moduleId: String
        get() = configuration.getNotNull(CommonConfigurationKeys.MODULE_NAME)

    internal val targetManager = TargetManager(configuration)
    internal val distribution = Distribution(configuration)

    private val libraryNames: List<String>
        get() {
            val fromCommandLine = configuration.getList(KonanConfigKeys.LIBRARY_FILES)
            if (configuration.get(KonanConfigKeys.NOSTDLIB) ?: false) {
                return fromCommandLine
            }
            return fromCommandLine + "stdlib"
        }

    private val repositories = configuration.getList(KonanConfigKeys.REPOSITORIES) ?: emptyList()
    private val resolver = KonanLibrarySearchPathResolver(repositories, distribution.klib, distribution.localKonanDir)
    private val librariesFound: List<File> by lazy {
        libraryNames.map{it -> resolver.resolve(it)}
    }

    internal val libraries: List<KonanLibraryReader> by lazy {
        val currentAbiVersion = configuration.get(KonanConfigKeys.ABI_VERSION)!!
        val target = targetManager.currentName
        // Here we have chosen a particular KonanLibraryReader implementation.
        librariesFound.map{it -> SplitLibraryReader(it, currentAbiVersion, target)}
    }

    private val loadedDescriptors = loadLibMetadata()

    internal val nativeLibraries: List<String> = 
        configuration.getList(KonanConfigKeys.NATIVE_LIBRARY_FILES)

    fun loadLibMetadata(): List<ModuleDescriptorImpl> {

        val allMetadata = mutableListOf<ModuleDescriptorImpl>()
        val specifics = KonanKotlinSpecifics(configuration.get(CommonConfigurationKeys.LANGUAGE_VERSION_SETTINGS)!!)

        for (klib in libraries) {
            profile("Loading ${klib.libraryName}") {
                val moduleDescriptor = klib.moduleDescriptor(specifics)
                allMetadata.add(moduleDescriptor)
            }
        }
        return allMetadata
    }

    internal val moduleDescriptors: List<ModuleDescriptorImpl> by lazy {
        for (module in loadedDescriptors) {
            // Yes, just to all of them.
            module.setDependencies(loadedDescriptors)
        }

        loadedDescriptors
    }
}
