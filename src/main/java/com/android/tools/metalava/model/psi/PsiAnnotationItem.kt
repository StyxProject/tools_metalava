/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.tools.metalava.model.psi

import com.android.SdkConstants.ATTR_VALUE
import com.android.tools.lint.detector.api.ConstantEvaluator
import com.android.tools.metalava.XmlBackedAnnotationItem
import com.android.tools.metalava.model.AnnotationArrayAttributeValue
import com.android.tools.metalava.model.AnnotationAttribute
import com.android.tools.metalava.model.AnnotationAttributeValue
import com.android.tools.metalava.model.AnnotationItem
import com.android.tools.metalava.model.AnnotationSingleAttributeValue
import com.android.tools.metalava.model.ClassItem
import com.android.tools.metalava.model.Codebase
import com.android.tools.metalava.model.DefaultAnnotationItem
import com.android.tools.metalava.model.Item
import com.intellij.psi.PsiAnnotation
import com.intellij.psi.PsiAnnotationMemberValue
import com.intellij.psi.PsiArrayInitializerMemberValue
import com.intellij.psi.PsiBinaryExpression
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiExpression
import com.intellij.psi.PsiField
import com.intellij.psi.PsiLiteral
import com.intellij.psi.PsiMethod
import com.intellij.psi.PsiReference
import com.intellij.psi.impl.JavaConstantExpressionEvaluator
import org.jetbrains.kotlin.asJava.elements.KtLightNullabilityAnnotation

class PsiAnnotationItem private constructor(
    override val codebase: PsiBasedCodebase,
    val psiAnnotation: PsiAnnotation
) : DefaultAnnotationItem(codebase) {
    private var attributes: List<AnnotationAttribute>? = null

    override fun toString(): String = toSource()

    override fun toSource(): String {
        val sb = StringBuilder(60)
        appendAnnotation(sb, psiAnnotation)
        return sb.toString()
    }

    private fun appendAnnotation(sb: StringBuilder, psiAnnotation: PsiAnnotation) {
        val qualifiedName = AnnotationItem.mapName(codebase, psiAnnotation.qualifiedName) ?: return

        val attributes = psiAnnotation.parameterList.attributes
        if (attributes.isEmpty()) {
            sb.append("@$qualifiedName")
            return
        }

        sb.append("@")
        sb.append(qualifiedName)
        sb.append("(")
        if (attributes.size == 1 && (attributes[0].name == null || attributes[0].name == ATTR_VALUE)) {
            // Special case: omit "value" if it's the only attribute
            appendValue(sb, attributes[0].value)
        } else {
            var first = true
            for (attribute in attributes) {
                if (first) {
                    first = false
                } else {
                    sb.append(", ")
                }
                sb.append(attribute.name ?: ATTR_VALUE)
                sb.append('=')
                appendValue(sb, attribute.value)
            }
        }
        sb.append(")")
    }

    override fun resolve(): ClassItem? {
        return codebase.findClass(psiAnnotation.qualifiedName ?: return null)
    }

    private fun appendValue(sb: StringBuilder, value: PsiAnnotationMemberValue?) {
        // Compute annotation string -- we don't just use value.text here
        // because that may not use fully qualified names, e.g. the source may say
        //  @RequiresPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
        // and we want to compute
        //  @android.support.annotation.RequiresPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION)
        when (value) {
            null -> sb.append("null")
            is PsiLiteral -> sb.append(constantToSource(value.value))
            is PsiReference -> {
                val resolved = value.resolve()
                when (resolved) {
                    is PsiField -> {
                        val containing = resolved.containingClass
                        if (containing != null) {
                            // If it's a field reference, see if it looks like the field is hidden; if
                            // so, inline the value
                            val cls = codebase.findOrCreateClass(containing)
                            val initializer = resolved.initializer
                            if (initializer != null) {
                                val fieldItem = cls.findField(resolved.name)
                                if (fieldItem == null || fieldItem.isHiddenOrRemoved()) {
                                    // Use the literal value instead
                                    val source = getConstantSource(initializer)
                                    if (source != null) {
                                        sb.append(source)
                                        return
                                    }
                                }
                            }
                            containing.qualifiedName?.let {
                                sb.append(it).append('.')
                            }
                        }

                        sb.append(resolved.name)
                    }
                    is PsiClass -> resolved.qualifiedName?.let { sb.append(it) }
                    else -> {
                        sb.append(value.text)
                    }
                }
            }
            is PsiBinaryExpression -> {
                appendValue(sb, value.lOperand)
                sb.append(' ')
                sb.append(value.operationSign.text)
                sb.append(' ')
                appendValue(sb, value.rOperand)
            }
            is PsiArrayInitializerMemberValue -> {
                sb.append('{')
                var first = true
                for (initializer in value.initializers) {
                    if (first) {
                        first = false
                    } else {
                        sb.append(", ")
                    }
                    appendValue(sb, initializer)
                }
                sb.append('}')
            }
            is PsiAnnotation -> {
                appendAnnotation(sb, value)
            }
            else -> {
                if (value is PsiExpression) {
                    val source = getConstantSource(value)
                    if (source != null) {
                        sb.append(source)
                        return
                    }
                }
                sb.append(value.text)
            }
        }
    }

    override fun isNonNull(): Boolean {
        if (psiAnnotation is KtLightNullabilityAnnotation &&
            psiAnnotation.qualifiedName == ""
        ) {
            // Hack/workaround: some UAST annotation nodes do not provide qualified name :=(
            return true
        }
        return super.isNonNull()
    }

    private fun getConstantSource(value: PsiExpression): String? {
        val constant = JavaConstantExpressionEvaluator.computeConstantExpression(value, false)
        return constantToExpression(constant)
    }

    override fun qualifiedName() = AnnotationItem.mapName(codebase, psiAnnotation.qualifiedName)

    override fun attributes(): List<AnnotationAttribute> {
        if (attributes == null) {
            val psiAttributes = psiAnnotation.parameterList.attributes
            attributes = if (psiAttributes.isEmpty()) {
                emptyList()
            } else {
                val list = mutableListOf<AnnotationAttribute>()
                for (parameter in psiAttributes) {
                    list.add(
                        PsiAnnotationAttribute(
                            codebase,
                            parameter.name ?: ATTR_VALUE, parameter.value ?: continue
                        )
                    )
                }
                list
            }
        }

        return attributes!!
    }

    companion object {
        fun create(codebase: PsiBasedCodebase, psiAnnotation: PsiAnnotation): PsiAnnotationItem {
            return PsiAnnotationItem(codebase, psiAnnotation)
        }

        fun create(codebase: PsiBasedCodebase, original: PsiAnnotationItem): PsiAnnotationItem {
            return PsiAnnotationItem(codebase, original.psiAnnotation)
        }

        // TODO: Inline this such that instead of constructing XmlBackedAnnotationItem
        // and then producing source and parsing it, produce source directly
        fun create(
            codebase: Codebase,
            xmlAnnotation: XmlBackedAnnotationItem,
            context: Item? = null
        ): PsiAnnotationItem {
            if (codebase is PsiBasedCodebase) {
                return codebase.createAnnotation(xmlAnnotation.toSource(), context)
            } else {
                codebase.unsupported("Converting to PSI annotation requires PSI codebase")
            }
        }
    }
}

class PsiAnnotationAttribute(
    codebase: PsiBasedCodebase,
    override val name: String,
    psiValue: PsiAnnotationMemberValue
) : AnnotationAttribute {
    override val value: AnnotationAttributeValue = PsiAnnotationValue.create(
        codebase, psiValue
    )
}

abstract class PsiAnnotationValue : AnnotationAttributeValue {
    companion object {
        fun create(codebase: PsiBasedCodebase, value: PsiAnnotationMemberValue): PsiAnnotationValue {
            return if (value is PsiArrayInitializerMemberValue) {
                PsiAnnotationArrayAttributeValue(codebase, value)
            } else {
                PsiAnnotationSingleAttributeValue(codebase, value)
            }
        }
    }

    override fun toString(): String = toSource()
}

class PsiAnnotationSingleAttributeValue(
    private val codebase: PsiBasedCodebase,
    private val psiValue: PsiAnnotationMemberValue
) : PsiAnnotationValue(), AnnotationSingleAttributeValue {
    override val valueSource: String = psiValue.text
    override val value: Any?
        get() {
            if (psiValue is PsiLiteral) {
                return psiValue.value
            }

            val value = ConstantEvaluator.evaluate(null, psiValue)
            if (value != null) {
                return value
            }

            return psiValue.text
        }

    override fun value(): Any? = value

    override fun toSource(): String = psiValue.text

    override fun resolve(): Item? {
        if (psiValue is PsiReference) {
            val resolved = psiValue.resolve()
            when (resolved) {
                is PsiField -> return codebase.findField(resolved)
                is PsiClass -> return codebase.findOrCreateClass(resolved)
                is PsiMethod -> return codebase.findMethod(resolved)
            }
        }
        return null
    }
}

class PsiAnnotationArrayAttributeValue(codebase: PsiBasedCodebase, private val value: PsiArrayInitializerMemberValue) :
    PsiAnnotationValue(), AnnotationArrayAttributeValue {
    override val values = value.initializers.map {
        create(codebase, it)
    }.toList()

    override fun toSource(): String = value.text
}
