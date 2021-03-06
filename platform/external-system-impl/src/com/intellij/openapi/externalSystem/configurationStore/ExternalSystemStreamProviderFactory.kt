/*
 * Copyright 2000-2017 JetBrains s.r.o.
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
package com.intellij.openapi.externalSystem.configurationStore

import com.intellij.configurationStore.FileStorageAnnotation
import com.intellij.configurationStore.StreamProviderFactory
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.catchAndLog
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.externalSystem.service.project.manage.ExternalProjectsDataStorage
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ex.ProjectEx
import com.intellij.openapi.roots.ExternalProjectSystemRegistry
import com.intellij.openapi.roots.ProjectModelElement
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.registry.Registry
import com.intellij.util.io.DataExternalizer
import com.intellij.util.io.EnumeratorStringDescriptor
import com.intellij.util.io.PersistentHashMap
import java.io.*
import java.util.*

private val EXTERNAL_STORAGE_ANNOTATION = FileStorageAnnotation(StoragePathMacros.MODULE_FILE, false, ExternalProjectStorage::class.java)
private val LOG = logger<ExternalSystemStreamProviderFactory>()

// todo handle module rename
internal class ExternalSystemStreamProviderFactory(private val project: Project) : StreamProviderFactory {
  val nameToData = createStorage(project)
  private var isStorageFlushed = true

  init {
    Disposer.register(project, Disposable { nameToData.close() })

    // flush on save to be sure that data is saved (it is easy to reimport if corrupted (force exit, blue screen), but we need to avoid it if possible)
    ApplicationManager.getApplication().messageBus
      .connect(project)
      .subscribe(ProjectEx.ProjectSaved.TOPIC, ProjectEx.ProjectSaved {
        if (it === project && !isStorageFlushed && nameToData.isDirty) {
          isStorageFlushed = false
          ApplicationManager.getApplication().executeOnPooledThread {
            try {
              LOG.catchAndLog { nameToData.force() }
            }
            finally {
              isStorageFlushed = true
            }
          }
        }
      })
  }

  override fun customizeStorageSpecs(component: PersistentStateComponent<*>, componentManager: ComponentManager, storages: List<Storage>, operation: StateStorageOperation): List<Storage>? {
    if (componentManager !is Module || component !is ProjectModelElement || !Registry.`is`("store.imported.project.elements.separately", false)) {
      return null
    }

    if (operation == StateStorageOperation.WRITE) {
      // Keep in mind - this call will require storage for module because module option values are used.
      // We cannot just check that module name exists in the nameToData - new external system module will be not in the nameToData because not yet saved.
      @Suppress("INTERFACE_STATIC_METHOD_CALL_FROM_JAVA6_TARGET")
      if (ExternalProjectSystemRegistry.getInstance().getExternalSource(componentManager) == null) {
        return null
      }
    }
    else {
      // on read we cannot check because on import module is just created and not yet marked as external system module,
      // so, we just add our storage as first and default storages in the end as fallback
      val result = ArrayList<Storage>(storages.size + 1)
      result.add(EXTERNAL_STORAGE_ANNOTATION)
      result.addAll(storages)
      return result
    }

    // todo we can return on StateStorageOperation.WRITE default iml storage and then somehow using StateStorageChooserEx return Resolution.CLEAR to remove data from iml
    return listOf(EXTERNAL_STORAGE_ANNOTATION)
  }
}

private fun createStorage(project: Project): PersistentHashMap<String, ByteArray> {
  val file = File(ExternalProjectsDataStorage.getProjectConfigurationDir(), "${project.locationHash}/projectConfiguration")

  fun createMap() = PersistentHashMap<String, ByteArray>(file, EnumeratorStringDescriptor.INSTANCE, object : DataExternalizer<ByteArray> {
    override fun read(`in`: DataInput): ByteArray {
      val available = (`in` as InputStream).available()
      val result = ByteArray(available)
      `in`.readFully(result)
      return result
    }

    override fun save(out: DataOutput, value: ByteArray) {
      out.write(value)
    }
  })

  try {
    return createMap()
  }
  catch (e: IOException) {
    // todo force project reimport
    LOG.info(e)
    FileUtil.delete(file)
  }
  return createMap()
}