/*
 * Copyright 2000-2018 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.inspections.collections

import com.intellij.codeInspection.ProblemHighlightType
import com.intellij.codeInspection.ProblemsHolder
import org.jetbrains.kotlin.backend.common.descriptors.isSuspend
import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.config.LanguageVersion
import org.jetbrains.kotlin.idea.KotlinBundle
import org.jetbrains.kotlin.idea.inspections.AssociateFunction
import org.jetbrains.kotlin.idea.inspections.ReplaceAssociateFunctionFix
import org.jetbrains.kotlin.idea.inspections.ReplaceAssociateFunctionInspection
import org.jetbrains.kotlin.js.resolve.JsPlatformAnalyzerServices
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.anyDescendantOfType
import org.jetbrains.kotlin.psi.psiUtil.collectDescendantsOfType
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.bindingContextUtil.getTargetFunction
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.calls.callUtil.getType
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.kotlin.types.isNullable
import org.jetbrains.kotlin.types.typeUtil.builtIns
import org.jetbrains.kotlin.types.typeUtil.isSubtypeOf

class SimplifiableCallChainInspection : AbstractCallChainChecker() {
    override fun buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): KtVisitorVoid {
        return qualifiedExpressionVisitor(fun(expression) {
            var conversion = findQualifiedConversion(expression, conversionGroups) check@{ conversion, firstResolvedCall, _, context ->
                // Do not apply on maps due to lack of relevant stdlib functions
                val firstReceiverType = firstResolvedCall.extensionReceiver?.type
                if (firstReceiverType != null) {
                    if (conversion.replacement == "mapNotNull" && KotlinBuiltIns.isPrimitiveArray(firstReceiverType)) return@check false
                    val builtIns = context[BindingContext.EXPRESSION_TYPE_INFO, expression]?.type?.builtIns ?: return@check false
                    val firstReceiverRawType = firstReceiverType.constructor.declarationDescriptor?.defaultType
                    if (firstReceiverRawType.isMap(builtIns)) return@check false
                }
                if (conversion.replacement.startsWith("joinTo")) {
                    // Function parameter in map must have String result type
                    if (!firstResolvedCall.hasLastFunctionalParameterWithResult(context) {
                            it.isSubtypeOf(JsPlatformAnalyzerServices.builtIns.charSequence.defaultType)
                        }
                    ) return@check false
                }
                if (conversion.replacement == "maxBy" || conversion.replacement == "minBy") {
                    val functionalArgumentReturnType = firstResolvedCall.lastFunctionalArgumentReturnType(context) ?: return@check false
                    if (functionalArgumentReturnType.isNullable()) return@check false
                }
                return@check conversion.enableSuspendFunctionCall || !containsSuspendFunctionCall(firstResolvedCall, context)
            } ?: return

            val associateFunction = getAssociateFunction(conversion, expression.receiverExpression)
            if (associateFunction != null) conversion = conversion.copy(replacement = associateFunction.functionName)

            val replacement = conversion.replacement
            val descriptor = holder.manager.createProblemDescriptor(
                expression,
                expression.firstCalleeExpression()!!.textRange.shiftRight(-expression.startOffset),
                KotlinBundle.message("call.chain.on.collection.type.may.be.simplified"),
                ProblemHighlightType.GENERIC_ERROR_OR_WARNING,
                isOnTheFly,
                SimplifyCallChainFix(conversion) { callExpression ->
                    val lastArgumentName = if (replacement.startsWith("joinTo")) Name.identifier("transform") else null
                    if (lastArgumentName != null) {
                        val lastArgument = callExpression.valueArgumentList?.arguments?.singleOrNull()
                        val argumentExpression = lastArgument?.getArgumentExpression()
                        if (argumentExpression != null) {
                            lastArgument.replace(createArgument(argumentExpression, lastArgumentName))
                        }
                    }
                    if (associateFunction != null) {
                        ReplaceAssociateFunctionFix.replaceLastStatementForAssociateFunction(callExpression, associateFunction)
                    }
                }
            )
            holder.registerProblem(descriptor)
        })
    }

    private fun ResolvedCall<*>.lastFunctionalArgumentReturnType(context: BindingContext): KotlinType? {
        val argument = valueArguments.entries.lastOrNull()?.value?.arguments?.firstOrNull()
        return when (val argumentExpression = argument?.getArgumentExpression()) {
            is KtLambdaExpression -> {
                val functionLiteral = argumentExpression.functionLiteral
                val body = argumentExpression.bodyExpression
                val lastStatementType = body?.statements?.lastOrNull()?.getType(context)
                val returnedTypes = body
                    ?.collectDescendantsOfType<KtReturnExpression> { it.getTargetFunction(context) == functionLiteral }
                    ?.mapNotNull { it.returnedExpression?.getType(context) }
                    .orEmpty()
                val types = listOfNotNull(lastStatementType) + returnedTypes
                types.firstOrNull { it.isNullable() } ?: types.firstOrNull()
            }
            is KtNamedFunction -> argumentExpression.typeReference?.let { context[BindingContext.TYPE, it] }
            else -> null
        }
    }

    private fun containsSuspendFunctionCall(resolvedCall: ResolvedCall<*>, context: BindingContext): Boolean {
        return resolvedCall.call.callElement.anyDescendantOfType<KtCallExpression> {
            it.getResolvedCall(context)?.resultingDescriptor?.isSuspend == true
        }
    }

    private fun getAssociateFunction(conversion: Conversion, expression: KtExpression): AssociateFunction? {
        if (conversion.replacement != "associate") return null
        if (expression !is KtDotQualifiedExpression) return null
        val (associateFunction, problemHighlightType) =
            ReplaceAssociateFunctionInspection.getAssociateFunctionAndProblemHighlightType(expression) ?: return null
        if (problemHighlightType == ProblemHighlightType.INFORMATION) return null
        if (associateFunction != AssociateFunction.ASSOCIATE_WITH && associateFunction != AssociateFunction.ASSOCIATE_BY) return null
        return associateFunction
    }

    private val conversionGroups = conversions.group()

    companion object {

        private val conversions = listOf(
            Conversion("kotlin.collections.filter", "kotlin.collections.first", "first"),
            Conversion("kotlin.collections.filter", "kotlin.collections.firstOrNull", "firstOrNull"),
            Conversion("kotlin.collections.filter", "kotlin.collections.last", "last"),
            Conversion("kotlin.collections.filter", "kotlin.collections.lastOrNull", "lastOrNull"),
            Conversion("kotlin.collections.filter", "kotlin.collections.single", "single"),
            Conversion("kotlin.collections.filter", "kotlin.collections.singleOrNull", "singleOrNull"),
            Conversion("kotlin.collections.filter", "kotlin.collections.isNotEmpty", "any"),
            Conversion("kotlin.collections.filter", "kotlin.collections.List.isEmpty", "none"),
            Conversion("kotlin.collections.sorted", "kotlin.collections.firstOrNull", "min"),
            Conversion("kotlin.collections.sorted", "kotlin.collections.lastOrNull", "max"),
            Conversion("kotlin.collections.sortedDescending", "kotlin.collections.firstOrNull", "max"),
            Conversion("kotlin.collections.sortedDescending", "kotlin.collections.lastOrNull", "min"),
            Conversion("kotlin.collections.sortedBy", "kotlin.collections.firstOrNull", "minBy"),
            Conversion("kotlin.collections.sortedBy", "kotlin.collections.lastOrNull", "maxBy"),
            Conversion("kotlin.collections.sortedByDescending", "kotlin.collections.firstOrNull", "maxBy"),
            Conversion("kotlin.collections.sortedByDescending", "kotlin.collections.lastOrNull", "minBy"),
            Conversion("kotlin.collections.sorted", "kotlin.collections.first", "min", withNotNullAssertion = true),
            Conversion("kotlin.collections.sorted", "kotlin.collections.last", "max", withNotNullAssertion = true),
            Conversion("kotlin.collections.sortedDescending", "kotlin.collections.first", "max", withNotNullAssertion = true),
            Conversion("kotlin.collections.sortedDescending", "kotlin.collections.last", "min", withNotNullAssertion = true),
            Conversion("kotlin.collections.sortedBy", "kotlin.collections.first", "minBy", withNotNullAssertion = true),
            Conversion("kotlin.collections.sortedBy", "kotlin.collections.last", "maxBy", withNotNullAssertion = true),
            Conversion("kotlin.collections.sortedByDescending", "kotlin.collections.first", "maxBy", withNotNullAssertion = true),
            Conversion("kotlin.collections.sortedByDescending", "kotlin.collections.last", "minBy", withNotNullAssertion = true),

            Conversion("kotlin.text.filter", "kotlin.text.first", "first"),
            Conversion("kotlin.text.filter", "kotlin.text.firstOrNull", "firstOrNull"),
            Conversion("kotlin.text.filter", "kotlin.text.last", "last"),
            Conversion("kotlin.text.filter", "kotlin.text.lastOrNull", "lastOrNull"),
            Conversion("kotlin.text.filter", "kotlin.text.single", "single"),
            Conversion("kotlin.text.filter", "kotlin.text.singleOrNull", "singleOrNull"),
            Conversion("kotlin.text.filter", "kotlin.text.isNotEmpty", "any"),
            Conversion("kotlin.text.filter", "kotlin.text.isEmpty", "none"),

            Conversion("kotlin.collections.map", "kotlin.collections.joinTo", "joinTo", enableSuspendFunctionCall = false),
            Conversion("kotlin.collections.map", "kotlin.collections.joinToString", "joinToString", enableSuspendFunctionCall = false),
            Conversion("kotlin.collections.map", "kotlin.collections.filterNotNull", "mapNotNull"),
            Conversion("kotlin.collections.map", "kotlin.collections.toMap", "associate"),

            Conversion("kotlin.collections.listOf", "kotlin.collections.filterNotNull", "listOfNotNull")
        ).map {
            when (val replacement = it.replacement) {
                "min", "max", "minBy", "maxBy" -> listOf(
                    it.copy(replacement = "${replacement}OrNull", replaceableLanguageVersion = LanguageVersion.KOTLIN_1_4),
                    it
                )
                else -> listOf(it)
            }
        }.flatten()
    }
}