/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.core.taskcontainers

import io.papermc.paperweight.core.ext
import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.tasks.mache.*
import io.papermc.paperweight.tasks.mache.RemapJar
import io.papermc.paperweight.tasks.softspoon.ApplyFeaturePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatches
import io.papermc.paperweight.tasks.softspoon.ApplyFilePatchesFuzzy
import io.papermc.paperweight.tasks.softspoon.FixupFilePatches
import io.papermc.paperweight.tasks.softspoon.RebuildFilePatches
import io.papermc.paperweight.tasks.softspoon.SetupPaperScript
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import io.papermc.paperweight.util.data.mache.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.*
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.SourceSet
import org.gradle.api.tasks.TaskContainer
import org.gradle.kotlin.dsl.*

open class SoftSpoonTasks(
    val project: Project,
    val allTasks: AllTasks,
    tasks: TaskContainer = project.tasks
) {

    lateinit var mache: MacheMeta

    val macheCodebook by project.configurations.registering {
        isTransitive = false
    }
    val macheRemapper by project.configurations.registering {
        isTransitive = false
    }
    val macheDecompiler by project.configurations.registering {
        isTransitive = false
    }
    val macheParamMappings by project.configurations.registering {
        isTransitive = false
    }
    val macheConstants by project.configurations.registering {
        isTransitive = false
    }
    val macheMinecraft by project.configurations.registering
    val macheMinecraftExtended by project.configurations.registering

    val macheRemapJar by tasks.registering(RemapJar::class) {
        group = "mache"
        serverJar.set(allTasks.extractFromBundler.flatMap { it.serverJar })
        serverMappings.set(allTasks.downloadMappings.flatMap { it.outputFile })

        remapperArgs.set(mache.remapperArgs)
        codebookClasspath.from(macheCodebook)
        minecraftClasspath.from(macheMinecraft)
        remapperClasspath.from(macheRemapper)
        paramMappings.from(macheParamMappings)
        constants.from(macheConstants)

        outputJar.set(layout.cache.resolve(FINAL_REMAPPED_CODEBOOK_JAR))
    }

    val macheDecompileJar by tasks.registering(DecompileJar::class) {
        group = "mache"
        inputJar.set(macheRemapJar.flatMap { it.outputJar })
        decompilerArgs.set(mache.decompilerArgs)

        minecraftClasspath.from(macheMinecraft)
        decompiler.from(macheDecompiler)

        outputJar.set(layout.cache.resolve(FINAL_DECOMPILE_JAR))
    }

    val collectAccessTransform by tasks.registering(CollectATsFromPatches::class) {
        group = "mache"

        patchDir.set(project.ext.paper.featurePatchDir)
    }

    val mergeCollectedAts by tasks.registering<MergeAccessTransforms> {
        firstFile.set(project.ext.paper.additionalAts.fileExists(project))
        secondFile.set(collectAccessTransform.flatMap { it.outputFile })
    }

    val setupMacheSources by tasks.registering(SetupVanilla::class) {
        group = "mache"
        description = "Setup vanilla source dir (applying mache patches and paper ATs)."

        mache.from(project.configurations.named(MACHE_CONFIG))
        macheOld.set(project.ext.macheOldPath)
        machePatches.set(layout.cache.resolve(PATCHES_FOLDER))
        ats.set(mergeCollectedAts.flatMap { it.outputFile })
        minecraftClasspath.from(macheMinecraft)

        libraries.from(allTasks.downloadPaperLibrariesSources.flatMap { it.outputDir }, allTasks.downloadMcLibrariesSources.flatMap { it.outputDir })
        paperPatches.from(project.ext.paper.sourcePatchDir, project.ext.paper.featurePatchDir)
        devImports.set(project.ext.paper.devImports.fileExists(project))

        inputFile.set(macheDecompileJar.flatMap { it.outputJar })
        predicate.set { Files.isRegularFile(it) && it.toString().endsWith(".java") }
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
    }

    val setupMacheResources by tasks.registering(SetupVanilla::class) {
        group = "mache"
        description = "Setup vanilla resources dir"

        inputFile.set(allTasks.extractFromBundler.flatMap { it.serverJar })
        predicate.set { Files.isRegularFile(it) && !it.toString().endsWith(".class") }
        outputDir.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
    }

    val applySourcePatches by tasks.registering(ApplyFilePatches::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla sources"

        input.set(setupMacheSources.flatMap { it.outputDir })
        output.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.ext.paper.sourcePatchDir)
        rejects.set(project.ext.paper.rejectsDir)
        gitFilePatches.set(project.ext.gitFilePatches)
    }

    val applySourcePatchesFuzzy by tasks.registering(ApplyFilePatchesFuzzy::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla sources"

        input.set(setupMacheSources.flatMap { it.outputDir })
        output.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.ext.paper.sourcePatchDir)
        rejects.set(project.ext.paper.rejectsDir)
        gitFilePatches.set(project.ext.gitFilePatches)
    }

    val applyResourcePatches by tasks.registering(ApplyFilePatches::class) {
        group = "softspoon"
        description = "Applies patches to the vanilla resources"

        input.set(setupMacheResources.flatMap { it.outputDir })
        output.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/resources") })
        patches.set(project.ext.paper.resourcePatchDir)
    }

    val applyFilePatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Applies all file patches"
        dependsOn(applySourcePatches, applyResourcePatches)
    }

    val applyFeaturePatches by tasks.registering(ApplyFeaturePatches::class) {
        group = "softspoon"
        description = "Applies all feature patches"
        dependsOn(applyFilePatches)

        repo.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.ext.paper.featurePatchDir)
    }

    val applyPatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Applies all patches"
        dependsOn(applyFilePatches, applyFeaturePatches)
    }

    val rebuildSourcePatches by tasks.registering(RebuildFilePatches::class) {
        group = "softspoon"
        description = "Rebuilds patches to the vanilla sources"

        minecraftClasspath.from(macheMinecraftExtended)
        atFile.set(project.ext.paper.additionalAts.fileExists(project))
        atFileOut.set(project.ext.paper.additionalAts.fileExists(project))

        base.set(layout.cache.resolve(BASE_PROJECT).resolve("sources"))
        input.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patches.set(project.ext.paper.sourcePatchDir)
        gitFilePatches.set(project.ext.gitFilePatches)
    }

    val rebuildResourcePatches by tasks.registering(RebuildFilePatches::class) {
        group = "softspoon"
        description = "Rebuilds patches to the vanilla resources"

        base.set(layout.cache.resolve(BASE_PROJECT).resolve("resources"))
        input.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/resources") })
        patches.set(project.ext.paper.resourcePatchDir)
    }

    val rebuildFilePatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Rebuilds all file patches"
        dependsOn(rebuildSourcePatches, rebuildResourcePatches)
    }

    val rebuildFeaturePatches by tasks.registering(RebuildGitPatches::class) {
        group = "softspoon"
        description = "Rebuilds all feature patches"
        dependsOn(rebuildFilePatches)

        inputDir.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
        patchDir.set(project.ext.paper.featurePatchDir)
        baseRef.set("file")
    }

    val rebuildPatches by tasks.registering(Task::class) {
        group = "softspoon"
        description = "Rebuilds all file patches"
        dependsOn(rebuildFilePatches, rebuildFeaturePatches)
    }

    val fixupSourcePatches by tasks.registering(FixupFilePatches::class) {
        group = "softspoon"
        description = "Puts the currently tracked source changes into the file patches commit"

        repo.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/java") })
    }

    val fixupResourcePatches by tasks.registering(FixupFilePatches::class) {
        group = "softspoon"
        description = "Puts the currently tracked resource changes into the file patches commit"

        repo.set(project.ext.serverProject.map { it.layout.projectDirectory.dir("src/vanilla/resources") })
    }

    val setupPaperScript by tasks.registering(SetupPaperScript::class) {
        group = "softspoon"
        description = "Creates a util script and installs it into path"

        root.set(project.projectDir)
    }

    fun afterEvaluate() {
        // load mache
        mache = this.project.configurations.named(MACHE_CONFIG).get().singleFile.toPath().openZip().use { zip ->
            return@use gson.fromJson<MacheMeta>(zip.getPath("/mache.json").readLines().joinToString("\n"))
        }
        println("Loaded mache ${mache.macheVersion} for minecraft ${mache.minecraftVersion}")

        // setup repos
        this.project.repositories {
            println("setup repos for ${project.name}")
            for (repository in mache.repositories) {
                maven(repository.url) {
                    name = repository.name
                    mavenContent {
                        for (group in repository.groups ?: listOf()) {
                            includeGroupByRegex(group + ".*")
                        }
                    }
                }
            }

            maven(MC_LIBRARY_URL) {
                name = "Minecraft"
            }
            mavenCentral()
        }

        val libsFile = project.layout.cache.resolve(SERVER_LIBRARIES_TXT)

        // setup mc deps
        macheMinecraft {
            withDependencies {
                project.dependencies {
                    val libs = libsFile.convertToPathOrNull()
                    if (libs != null && libs.exists()) {
                        libs.forEachLine { line ->
                            add(create(line))
                        }
                    }
                }
            }
        }
        macheMinecraftExtended {
            extendsFrom(macheMinecraft.get())
            withDependencies {
                project.dependencies {
                    add(create(project.files(project.layout.cache.resolve(FINAL_REMAPPED_CODEBOOK_JAR))))
                }
            }
        }

        // setup mache deps
        this.project.dependencies {
            mache.dependencies.codebook.forEach {
                "macheCodebook"(it.toMavenString())
            }
            mache.dependencies.paramMappings.forEach {
                "macheParamMappings"(it.toMavenString())
            }
            mache.dependencies.constants.forEach {
                "macheConstants"(it.toMavenString())
            }
            mache.dependencies.remapper.forEach {
                "macheRemapper"(it.toMavenString())
            }
            mache.dependencies.decompiler.forEach {
                "macheDecompiler"(it.toMavenString())
            }
        }

        this.project.ext.serverProject.get().setupServerProject(libsFile)
    }

    private fun Project.setupServerProject(libsFile: Path) {
        if (!projectDir.exists()) {
            return
        }

        // minecraft deps
        val macheMinecraft by configurations.creating {
            withDependencies {
                dependencies {
                    // setup mc deps
                    val libs = libsFile.convertToPathOrNull()
                    if (libs != null && libs.exists()) {
                        libs.forEachLine { line ->
                            add(create(line))
                        }
                    }
                }
            }
        }

        // impl extends minecraft
        configurations.named(JavaPlugin.IMPLEMENTATION_CONFIGURATION_NAME) {
            extendsFrom(macheMinecraft)
        }

        // repos
        repositories {
            mavenCentral()
            maven(PAPER_MAVEN_REPO_URL)
            maven(MC_LIBRARY_URL)
        }

        // add vanilla source set
        the<JavaPluginExtension>().sourceSets.named(SourceSet.MAIN_SOURCE_SET_NAME) {
            java {
                srcDirs(projectDir.resolve("src/vanilla/java"))
            }
            resources {
                srcDirs(projectDir.resolve("src/vanilla/resources"))
            }
        }
    }
}
