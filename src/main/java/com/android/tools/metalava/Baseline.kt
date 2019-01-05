/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.tools.metalava

import com.android.tools.metalava.doclava1.Errors
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.FieldItem
import com.android.tools.metalava.model.Item
import com.android.tools.metalava.model.MethodItem
import com.android.tools.metalava.model.PackageItem
import com.android.tools.metalava.model.ParameterItem
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiField
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiPackage
import com.intellij.psi.PsiParameter
import org.jetbrains.kotlin.psi.psiUtil.parameterIndex
import java.io.File
import java.io.PrintWriter

const val DEFAULT_BASELINE_NAME = "baseline.txt"

class Baseline(
    val file: File,
    var create: Boolean = !file.isFile,
    private var format: FileFormat = FileFormat.BASELINE,
    private var headerComment: String = ""
) {

    /** Map from issue id to element id to message */
    private val map = HashMap<Errors.Error, MutableMap<String, String>>()

    init {
        if (file.isFile && !create) {
            // We've set a baseline for a nonexistent file: read it
            read()
        }
    }

    /** Returns true if the given error is listed in the baseline, otherwise false */
    fun mark(element: Item, message: String, error: Errors.Error): Boolean {
        val elementId = getBaselineKey(element)
        return mark(elementId, message, error)
    }

    /** Returns true if the given error is listed in the baseline, otherwise false */
    fun mark(element: PsiElement, message: String, error: Errors.Error): Boolean {
        val elementId = getBaselineKey(element)
        return mark(elementId, message, error)
    }

    /** Returns true if the given error is listed in the baseline, otherwise false */
    fun mark(file: File, message: String, error: Errors.Error): Boolean {
        val elementId = getBaselineKey(file)
        return mark(elementId, message, error)
    }

    private fun mark(elementId: String, message: String, error: Errors.Error): Boolean {
        val idMap: MutableMap<String, String>? = map[error]

        if (create) {
            val newIdMap = idMap ?: run {
                val new = HashMap<String, String>()
                map[error] = new
                new
            }
            newIdMap[elementId] = message
            // When creating baselines don't report errors
            return true
        }

        val oldMessage: String? = idMap?.get(elementId)
        if (oldMessage != null) {
            // for now not matching messages; the id's are unique enough and allows us
            // to tweak error messages compatibly without recording all the deltas here
            return true
        }
        return false
    }

    private fun getBaselineKey(element: Item): String {
        return when (element) {
            is ClassItem -> element.qualifiedName()
            is MethodItem -> element.containingClass().qualifiedName() + "#" +
                element.name() + "(" + element.parameters().joinToString { it.type().toSimpleType() } + ")"
            is FieldItem -> element.containingClass().qualifiedName() + "#" + element.name()
            is PackageItem -> element.qualifiedName()
            is ParameterItem -> getBaselineKey(element.containingMethod()) + " parameter #" + element.parameterIndex
            else -> element.describe(false)
        }
    }

    private fun getBaselineKey(element: PsiElement): String {
        return when (element) {
            is PsiClass -> element.qualifiedName ?: element.name ?: "?"
            is PsiMethod -> {
                val containingClass = element.containingClass
                val name = element.name
                val parameterList = "(" + element.parameterList.parameters.joinToString { it.type.canonicalText } + ")"
                if (containingClass != null) {
                    getBaselineKey(containingClass) + "#" + name + parameterList
                } else {
                    name + parameterList
                }
            }
            is PsiField -> {
                val containingClass = element.containingClass
                val name = element.name
                if (containingClass != null) {
                    getBaselineKey(containingClass) + "#" + name
                } else {
                    name
                }
            }
            is PsiPackage -> element.qualifiedName
            is PsiParameter -> {
                val method = element.declarationScope.parent
                if (method is PsiMethod) {
                    getBaselineKey(method) + " parameter #" + element.parameterIndex()
                } else {
                    "?"
                }
            }
            is PsiFile -> {
                val virtualFile = element.virtualFile
                val file = VfsUtilCore.virtualToIoFile(virtualFile)
                return getBaselineKey(file)
            }
            else -> element.toString()
        }
    }

    private fun getBaselineKey(file: File): String {
        val path = file.path
        for (sourcePath in options.sourcePath) {
            if (path.startsWith(sourcePath.path)) {
                return path.substring(sourcePath.path.length).replace('\\', '/').removePrefix("/")
            }
        }

        return path.replace('\\', '/')
    }

    fun close() {
        if (create) {
            write()
        }
    }

    private fun read() {
        file.readLines(Charsets.UTF_8).forEach { line ->
            if (!(line.startsWith("//") || line.startsWith("#") || line.isBlank() || line.startsWith(" "))) {
                val idEnd = line.indexOf(':')
                val elementEnd = line.indexOf(':', idEnd + 1)
                if (idEnd == -1 || elementEnd == -1) {
                    println("Invalid metalava baseline format: $line")
                }
                val errorId = line.substring(0, idEnd).trim()
                val elementId = line.substring(idEnd + 2, elementEnd).trim()

                // For now we don't need the actual messages since we're only matching by
                // issue id and API location, so don't bother reading. (These are listed
                // on separate, indented, lines, so to read them we'd need to alternate
                // line readers.)
                val message = ""

                val error = Errors.findErrorById(errorId)
                if (error == null) {
                    println("Invalid metalava baseline file: unknown error id '$errorId'")
                } else {
                    val newIdMap = map[error] ?: run {
                        val new = HashMap<String, String>()
                        map[error] = new
                        new
                    }
                    newIdMap[elementId] = message
                }
            }
        }
    }

    private fun write() {
        if (!map.isEmpty()) {
            val sb = StringBuilder()
            sb.append(format.header())
            sb.append(headerComment)

            map.keys.asSequence().sortedBy { it.name ?: it.code.toString() }.forEach { error ->
                val idMap = map[error]
                idMap?.keys?.sorted()?.forEach { elementId ->
                    val message = idMap[elementId]!!
                    sb.append(error.name ?: error.code.toString()).append(": ")
                    sb.append(elementId)
                    sb.append(":\n    ")
                    sb.append(message).append('\n')
                }
                sb.append("\n\n")
            }
            file.parentFile?.mkdirs()
            file.writeText(sb.toString(), Charsets.UTF_8)
        } else {
            file.delete()
        }
    }

    fun dumpStats(writer: PrintWriter) {
        val counts = mutableMapOf<Errors.Error, Int>()
        map.keys.asSequence().forEach { error ->
            val idMap = map[error]
            val count = idMap?.count() ?: 0
            counts[error] = count
        }

        writer.println("Baseline issue type counts:")
        writer.println("" +
            "    Count Issue Id                       Severity\n" +
            "    ---------------------------------------------\n")
        val list = counts.entries.toMutableList()
        list.sortWith(compareBy({ -it.value }, { it.key.name ?: it.key.code.toString() }))
        var total = 0
        for (entry in list) {
            val count = entry.value
            val issue = entry.key
            writer.println("    ${String.format("%5d", count)} ${String.format("%-30s", issue.name)} ${issue.level}")
            total += count
        }
        writer.println("" +
            "    ---------------------------------------------\n" +
            "    ${String.format("%5d", total)}")
        writer.println()
    }
}