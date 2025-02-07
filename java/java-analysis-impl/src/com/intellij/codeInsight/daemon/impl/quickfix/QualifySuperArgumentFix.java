/*
 * Copyright 2000-2016 JetBrains s.r.o.
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

package com.intellij.codeInsight.daemon.impl.quickfix;

import com.intellij.codeInsight.daemon.impl.HighlightInfo;
import com.intellij.codeInsight.intention.FileModifier;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.refactoring.util.RefactoringChangeUtil;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class QualifySuperArgumentFix extends QualifyThisOrSuperArgumentFix {
  public QualifySuperArgumentFix(@NotNull PsiExpression expression, @NotNull PsiClass psiClass) {
    super(expression, psiClass);
  }

  @Override
  public @Nullable FileModifier getFileModifierForPreview(@NotNull PsiFile target) {
    return new QualifySuperArgumentFix(PsiTreeUtil.findSameElementInCopy(myExpression, target), myPsiClass);
  }

  @Override
  protected String getQualifierText() {
    return PsiKeyword.SUPER;
  }

  @Override
  protected PsiExpression getQualifier(PsiManager manager) {
    return RefactoringChangeUtil.createSuperExpression(manager, myPsiClass);
  }

  public static void registerQuickFixAction(@NotNull PsiSuperExpression expr, HighlightInfo highlightInfo) {
    LOG.assertTrue(expr.getQualifier() == null);
    final PsiClass containingClass = PsiTreeUtil.getParentOfType(expr, PsiClass.class);
    if (containingClass != null) {
      final PsiMethodCallExpression callExpression = PsiTreeUtil.getParentOfType(expr, PsiMethodCallExpression.class);
      if (callExpression != null) {
        final PsiElementFactory elementFactory = JavaPsiFacade.getElementFactory(callExpression.getProject());
        for (PsiClass superClass : containingClass.getSupers()) {
          if (superClass.isInterface()) {
            final PsiMethodCallExpression copy = (PsiMethodCallExpression)callExpression.copy();
            final PsiExpression superQualifierCopy = copy.getMethodExpression().getQualifierExpression();
            LOG.assertTrue(superQualifierCopy != null);
            superQualifierCopy.delete();
            PsiMethod method;
            try {
              method = ((PsiMethodCallExpression)elementFactory.createExpressionFromText(copy.getText(), superClass)).resolveMethod();
            }
            catch (IncorrectOperationException e) {
              LOG.info(e);
              return;
            }
            if (method != null && !method.hasModifierProperty(PsiModifier.ABSTRACT)) {
              QuickFixAction.registerQuickFixAction(highlightInfo, new QualifySuperArgumentFix(expr, superClass));
            }
          }
        }
      }
    }
  }
}
