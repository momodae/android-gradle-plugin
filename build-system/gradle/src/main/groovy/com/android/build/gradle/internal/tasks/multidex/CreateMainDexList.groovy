/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */



package com.android.build.gradle.internal.tasks.multidex
import com.android.annotations.Nullable
import com.android.build.gradle.internal.tasks.BaseTask
import com.android.ide.common.internal.CommandLineRunner
import com.google.common.base.Charsets
import com.google.common.base.Joiner
import com.google.common.collect.Lists
import com.google.common.collect.Sets
import com.google.common.io.Files
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
/**
 * Task to create the main (non-obfuscated) list of classes to keep.
 * It uses a jar containing all the classes, as well as a shrinked jar file created by proguard.
 *
 * Optionally, it can use a manual list of classes/jars to keep.
 */
public class CreateMainDexList extends BaseTask {
    @InputFile @Optional
    File allClassesJarFile

    @InputFile
    File componentsJarFile

    @OutputFile
    File outputFile

    @InputFile @Optional
    File includeInMainDexJarFile

    @InputFile @Optional
    File mainDexListFile

    @InputFile
    File getDxJar() {
        plugin.ensureTargetSetup()
        return builder.getDxJar()
    }

    @TaskAction
    void output() {
        if (getAllClassesJarFile() == null) {
            throw new NullPointerException("No input file")
        }

        // manifest components plus immediate dependencies must be in the main dex.
        File _allClassesJarFile = getAllClassesJarFile()
        Set<String> mainDexClasses = callDx(_allClassesJarFile, getComponentsJarFile())

        // add additional classes specified via a jar file.
        File _includeInMainDexJarFile = getIncludeInMainDexJarFile()
        if (_includeInMainDexJarFile != null) {
            // proguard shrinking is overly aggressive when it comes to removing
            // interface classes: even if an interface is implemented by a concrete
            // class, if no code actually references the interface class directly
            // (i.e., code always references the concrete class), proguard will
            // remove the interface class when shrinking.  This is problematic,
            // as the runtime verifier still needs the interface class to be
            // present, or the concrete class won't be valid.  Use a
            // ClassReferenceListBuilder here (only) to pull in these missing
            // interface classes.  Note that doing so brings in other unnecessary
            // stuff, too; next time we're low on main dex space, revisit this!
            mainDexClasses.addAll(callDx(_allClassesJarFile, _includeInMainDexJarFile))
        }

        if (mainDexListFile != null) {
            Set<String> mainDexList = new HashSet<String>(Files.readLines(mainDexListFile, Charsets.UTF_8))
            mainDexClasses.addAll(mainDexList)
        }

        String fileContent = Joiner.on(System.getProperty("line.separator")).join(mainDexClasses)

        Files.write(fileContent, getOutputFile(), Charsets.UTF_8)
    }

    private Set<String> callDx(File path, File jarOfRoots) {
        File dxJar = getDxJar()

        List<String> command = Lists.newArrayList()

        command << "java"
        command << "-Xmx1024M"
        command << "-cp"
        command << dxJar.absolutePath

        command << "com.android.multidex.ClassReferenceListBuilder"

        command << jarOfRoots.absolutePath
        command << path.absolutePath

        OutputGrabber grabber = new OutputGrabber()
        plugin.androidBuilder.commandLineRunner.runCmdLine(command, grabber, null)

        return Sets.newHashSet(grabber.lines)
    }

    private final static class OutputGrabber extends CommandLineRunner.CommandLineOutput {
        List<String> lines = Lists.newArrayList()

        @Override
        void out(@Nullable String line) {
            if (line != null) {
                lines.add(line)
            }
        }

        List<String> getLines() {
            return lines
        }
    }
}
