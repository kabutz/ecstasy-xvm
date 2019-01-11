package org.xvm.compiler.ast;


import org.xvm.asm.Argument;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.Op;

import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.JumpNotNull;
import org.xvm.asm.op.Label;

import org.xvm.compiler.Compiler;
import org.xvm.compiler.Token;

import org.xvm.util.Severity;


/**
 * The "Elvis" expression, which is used to optionally substitute the value of the second expression
 * iff the value of the first expression is null.
 *
 * <ul>
 * <li><tt>COND_ELSE:  "?:"</tt> - the "elvis" operator</li>
 * </ul>
 */
public class ElvisExpression
        extends BiExpression
    {
    // ----- constructors --------------------------------------------------------------------------

    public ElvisExpression(Expression expr1, Token operator, Expression expr2)
        {
        super(expr1, operator, expr2);
        }


    // ----- compilation ---------------------------------------------------------------------------

    @Override
    public TypeConstant getImplicitType(Context ctx)
        {
        TypeConstant type1 = expr1.getImplicitType(ctx);
        TypeConstant type2 = expr2.getImplicitType(ctx);
        if (type1 == null || type2 == null)
            {
            return null;
            }

        // nulls in the first expression are eliminated by using the second expression
        type1 = type1.removeNullable(pool());

        TypeConstant typeResult = Op.selectCommonType(type1, type2, ErrorListener.BLACKHOLE);

        // hey, wouldn't it be nice if we could just do this?
        //
        //   return typeResult ?: pool().ensureIntersectionTypeConstant(type1, type2);
        //
        return typeResult == null
                ? pool().ensureIntersectionTypeConstant(type1, type2)
                : typeResult;
        }

    @Override
    public TypeFit testFit(Context ctx, TypeConstant typeRequired)
        {
        TypeFit fit = expr1.testFit(ctx, typeRequired.ensureNullable(pool()));
        if (fit.isFit())
            {
            fit.combineWith(expr2.testFit(ctx, typeRequired));
            }
        return fit;
        }

    @Override
    protected Expression validate(Context ctx, TypeConstant typeRequired, ErrorListener errs)
        {
        ConstantPool pool     = pool();
        TypeFit      fit      = TypeFit.Fit;
        TypeConstant type1Req = typeRequired == null ? null : typeRequired.ensureNullable(pool);
        Expression   expr1New = expr1.validate(ctx, type1Req, errs);
        TypeConstant type1    = null;
        if (expr1New == null)
            {
            fit = TypeFit.NoFit;
            }
        else
            {
            expr1 = expr1New;
            type1 = expr1New.getType();
            }

        TypeConstant type2Req = type1 == null ? null :
                Op.selectCommonType(type1.removeNullable(pool), null, errs);

        if (typeRequired != null && (type2Req == null || !expr2.testFit(ctx, type2Req).isFit()))
            {
            type2Req = typeRequired;
            }
        Expression expr2New = expr2.validate(ctx, type2Req, errs);
        if (expr2New == null)
            {
            fit = TypeFit.NoFit;
            }
        else
            {
            expr2 = expr2New;
            }

        if (!fit.isFit())
            {
            return finishValidation(typeRequired, null, fit, null, errs);
            }

        if (type1.isOnlyNullable())
            {
            expr1New.log(errs, Severity.ERROR, Compiler.ELVIS_ONLY_NULLABLE);
            return replaceThisWith(expr2New);
            }

        if (!pool.typeNull().isA(type1))
            {
            expr1New.log(errs, Severity.ERROR, Compiler.ELVIS_NOT_NULLABLE);
            return replaceThisWith(expr1New);
            }

        TypeConstant type1Non   = type1.removeNullable(pool);
        TypeConstant type2      = expr2New.getType();
        TypeConstant typeResult = Op.selectCommonType(type1Non, type2, errs);
        if (typeResult == null)
            {
            typeResult = pool.ensureIntersectionTypeConstant(type1Non, type2);
            }

        // in the unlikely event that one or both of the sub expressions are constant, it may be
        // possible to calculate the constant value of this elvis expression
        Constant constVal = null;
        if (expr1New.isConstant())
            {
            Constant const1 = expr1New.toConstant();
            if (const1.equals(pool.valNull()))
                {
                if (expr2New.isConstant())
                    {
                    constVal = expr2New.toConstant();
                    }
                }
            else
                {
                constVal = const1;
                }
            }

        return finishValidation(typeRequired, typeResult, fit, constVal, errs);
        }

    @Override
    public boolean isCompletable()
        {
        // these can complete if the first expression can complete, because the result can
        // be calculated from the first expression, depending on what its answer is
        return expr1.isCompletable();
        }

    @Override
    public Argument generateArgument(
            Context ctx, Code code, boolean fLocalPropOk, boolean fUsedOnce, ErrorListener errs)
        {
        ConstantPool pool = pool();

        if (isConstant() || pool.typeNull().isA(getType()))
            {
            return super.generateArgument(ctx, code, fLocalPropOk, fUsedOnce, errs);
            }

        TypeConstant typeTemp = getType().ensureNullable(pool);
        Assignable   var      = createTempVar(code, typeTemp, false, errs);
        generateAssignment(ctx, code, var, errs);
        return var.getRegister();
        }

    @Override
    public void generateAssignment(Context ctx, Code code, Assignable LVal, ErrorListener errs)
        {
        if (isConstant() || !LVal.isLocalArgument() || !pool().typeNull().isA(LVal.getType()))
            {
            super.generateAssignment(ctx, code, LVal, errs);
            return;
            }

        Label labelEnd = new Label("end_?:_" + m_nLabel);

        expr1.generateAssignment(ctx, code, LVal, errs);
        code.add(new JumpNotNull(LVal.getLocalArgument(), labelEnd));
        expr2.generateAssignment(ctx, code, LVal, errs);
        code.add(labelEnd);
        }


    // ----- fields --------------------------------------------------------------------------------

    private static    int m_nCounter;
    private transient int m_nLabel;
    }
