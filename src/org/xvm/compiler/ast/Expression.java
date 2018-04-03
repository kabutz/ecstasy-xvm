package org.xvm.compiler.ast;


import java.util.Arrays;

import org.xvm.asm.Constant;
import org.xvm.asm.ErrorListener;
import org.xvm.asm.MethodStructure.Code;
import org.xvm.asm.Op;
import org.xvm.asm.Op.Argument;
import org.xvm.asm.Register;

import org.xvm.asm.constants.ConditionalConstant;
import org.xvm.asm.constants.MethodConstant;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.asm.op.I_Set;
import org.xvm.asm.op.Invoke_01;
import org.xvm.asm.op.JumpFalse;
import org.xvm.asm.op.JumpTrue;
import org.xvm.asm.op.L_Set;
import org.xvm.asm.op.Label;
import org.xvm.asm.op.Move;
import org.xvm.asm.op.P_Set;

import org.xvm.compiler.Compiler;

import org.xvm.compiler.ast.Statement.Context;

import org.xvm.util.Severity;

import static org.xvm.util.Handy.checkElementsNonNull;


/**
 * Base class for all Ecstasy expressions.
 * <p/>
 * Concepts:
 * <pre>
 * 1. You've got to be able to ask an expression some simple, obvious questions:
 *    a. Single or multi? Most expressions represent a single L-value or R-value, but some
 *       expressions can represent a number of L-values or R-values
 *       - including "conditional" results
 *    b. What is your type? (implicit type)
 *    c. Are you a constant? (i.e. could you be if I asked you to?)
 *    d. Is it possible for you to complete? (e.g. T0D0 does not)
 *    e. Can I use you as an L-Value, i.e. something that I can assign something to?
 *    f. Do you short-circuit? e.g. "a?.b" will short-circuit iff "a" is Null
 *
 * 2. When an expression is capable of being an L-Value, there has to be some sort of L-Value
 *    representation that it can provide. For example, in the assignment "a.b.c.d = e", the left
 *    side expression of "a.b.c.d" needs to produce some code that does the "a.b.c" part, and
 *    then yields an L-Value structure that says "it's the 'd' property on this 'c' thing that
 *    I've resolved up to".
 *    a. local variable (register)
 *       - including the "black hole"
 *    b. property (target and property identifier)
 *       - including knowledge as to whether it can be treated as a "local" property
 *    c. array index (target and index)
 *       - including support for multi-dimensioned arrays (multiple indexes)
 *
 * 3. When an expression is capable of being an L-Value, it can represent more than one L-Value,
 *    for example when it is the left-hand-side of a multi-assignment, or when it represents a
 *    "conditional" value. That means that any expression that can provide more than one L-Value
 *    must implement a plural form of that method.
 *
 * 4. When an expression is capable of being an R-Value, it can represent more than one R-Value,
 *    for example when it is the right-hand-side of a multi-assignment, or when it represents a
 *    "conditional" value. That means that any expression that can provide more than one R-Value
 *    must implement a plural form of that method.
 *
 * 5. When an expression is being used as an R-Value,
 *    a. as a Constant of a specified type
 *    b. as one or more arguments
 *    c. as a conditional jump
 *    d. assignment to one or more L-values
 *
 * 6. An expression that is allowed to short-circuit must be provided with a label to which it can
 *    short-circuit. This also affects the definite assignment rules.
 */
public abstract class Expression
        extends AstNode
    {
    // ----- accessors -----------------------------------------------------------------------------

    @Override
    protected boolean usesSuper()
        {
        for (AstNode node : children())
            {
            if (!(node instanceof ComponentStatement) && node.usesSuper())
                {
                return true;
                }
            }

        return false;
        }

    /**
     * @return this expression, converted to a type expression
     */
    public TypeExpression toTypeExpression()
        {
        return new BadTypeExpression(this);
        }

    /**
     * Validate that this expression is structurally correct to be a link-time condition.
     * <p/><code><pre>
     * There are only a few expression forms that are permitted:
     * 1. StringLiteral "." "defined"
     * 2. QualifiedName "." "present"
     * 3. QualifiedName "." "versionMatches" "(" VersionLiteral ")"
     * 4. Any of 1-3 and 5 negated using "!"
     * 5. Any two of 1-5 combined using "&", "&&", "|", or "||"
     * </pre></code>
     *
     * @param errs  the error listener to log any errors to
     *
     * @return true if the expression is structurally valid
     */
    public boolean validateCondition(ErrorListener errs)
        {
        log(errs, Severity.ERROR, Compiler.ILLEGAL_CONDITIONAL);
        return false;
        }

    /**
     * @return this expression as a link-time conditional constant
     */
    public ConditionalConstant toConditionalConstant()
        {
        throw notImplemented();
        }


    // ----- Expression compilation ----------------------------------------------------------------

    // Expressions go through a few stages of compilation. Initially, the expressions must determine
    // its arity and its type(s) etc., but there exists more than one possible result in some cases,
    // based on what type is expected or required of the expression. Similarly, the resulting type
    // of this expression can affect the type of a containing expression. To accomodate this, the
    // expression has to be able to answer some hypothetical questions _before_ it validates, and
    // _all_ questions after it validates.

    /**
     * Represents the ability of an expression to yield a requested type:
     * <ul>
     * <li>{@code NoFit} - the expression can <b>not</b> yield the requested type;</li>
     * <li>{@code ConvPackUnpack} - the expression can yield the requested type via a combination of
     *     {@code @Auto} type conversion, tuple packing, and tuple unpacking;</li>
     * <li>{@code ConvPack} - the expression can yield the requested type via a combination of
     *     {@code @Auto} type conversion and tuple packing;</li>
     * <li>{@code ConvUnpack} - the expression can yield the requested type via a combination of
     *     {@code @Auto} type conversion and tuple unpacking;</li>
     * <li>{@code Conv} - the expression can yield the requested type via {@code @Auto} type
     *     conversion;</li>
     * <li>{@code PackUnpack} - the expression can yield the requested type via a combination of
     *     tuple packing and tuple unpacking;</li>
     * <li>{@code Pack} - the expression can yield the requested type via tuple packing;</li>
     * <li>{@code Unpack} - the expression can yield the requested type via tuple unpacking;</li>
     * <li>{@code Fit} - the expression can yield the requested type.</li>
     * </ul>
     */
    public enum TypeFit
        {
        NoFit         (0b0000),
        ConvPackUnpack(0b1111),
        ConvPack      (0b1011),
        ConvUnpack    (0b0111),
        Conv          (0b0011),
        PackUnpack    (0b1101),
        Pack          (0b0101),
        Unpack        (0b1001),
        Fit           (0b0001);

        /**
         * Constructor.
         *
         * @param nFlags  bit flags defining how good a fit the TypeFit is
         */
        TypeFit(int nFlags)
            {
            FLAGS = nFlags;
            }

        /**
         * @return true iff the type fits, regardless of whether it needs conversion or packing or
         *         unpacking
         */
        public boolean isFit()
            {
            return (FLAGS & FITS) != 0;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, plus fits
         */
        public TypeFit ensureFit()
            {
            return isFit()
                    ? this
                    : Fit;
            }

        /**
         * @return true iff the type goes through at least one "@Auto" conversion in order to fit
         */
        public boolean isConverting()
            {
            return (FLAGS & CONVERTS) != 0;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, plus type conversion
         */
        public TypeFit addConversion()
            {
            return isFit()
                    ? forFlags(FLAGS | CONVERTS)
                    : NoFit;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, minus type conversion
         */
        public TypeFit removeConversion()
            {
            return isConverting()
                    ? forFlags(FLAGS & ~CONVERTS)
                    : this;
            }

        /**
         * @return true iff the type goes through a tuple creation
         */
        public boolean isPacking()
            {
            return (FLAGS & PACKS) != 0;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, plus Tuple packing
         */
        public TypeFit addPack()
            {
            return isFit()
                    ? forFlags(FLAGS | PACKS)
                    : NoFit;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, minus Tuple packing
         */
        public TypeFit removePack()
            {
            return isPacking()
                    ? forFlags(FLAGS & ~PACKS)
                    : this;
            }

        /**
         * @return true iff the type goes through a tuple extraction
         */
        public boolean isUnpacking()
            {
            return (FLAGS & UNPACKS) != 0;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, plus Tuple unpacking
         */
        public TypeFit addUnpack()
            {
            return isFit()
                    ? forFlags(FLAGS | UNPACKS)
                    : NoFit;
            }

        /**
         * @return a TypeFit that does everything this TypeFit does, minus Tuple unpacking
         */
        public TypeFit removeUnpack()
            {
            return isConverting()
                    ? forFlags(FLAGS & ~UNPACKS)
                    : this;
            }

        /**
         * Produce a fit that combines this fit and that fit.
         *
         * @param that  the other fit
         *
         * @return a fit that combines all the attributes of this fit and that fit
         */
        public TypeFit combineWith(TypeFit that)
            {
            return forFlags(this.FLAGS | that.FLAGS);
            }

        /**
         * Determine which is the best fit, and return that best fit.
         *
         * @param that  the other fit
         *
         * @return whichever fit is considered better
         */
        public TypeFit betterOf(TypeFit that)
            {
            return this.ordinal() > that.ordinal() ? this : that;
            }

        /**
         * Determine if another fit is better than this fit.
         *
         * @param that  the other fit
         *
         * @return true iff the other fit is considered to be a better fit than this fit
         */
        public boolean betterThan(TypeFit that)
            {
            return this.ordinal() > that.ordinal();
            }

        /**
         * Determine if another fit is worse than this fit.
         *
         * @param that  the other fit
         *
         * @return true iff the other fit is considered to be a worse fit than this fit
         */
        public boolean worseThan(TypeFit that)
            {
            return this.ordinal() < that.ordinal();
            }

        /**
         * Look up a TypeFit enum by its ordinal.
         *
         * @param i  the ordinal
         *
         * @return the TypeFit enum for the specified ordinal
         */
        public static TypeFit valueOf(int i)
            {
            return BY_ORDINAL[i];
            }

        /**
         * Look up a TypeFit enum by its flags.
         *
         * @param nFlags  the flags
         *
         * @return the TypeFit enum for the specified ordinal
         */
        public static TypeFit forFlags(int nFlags)
            {
            if (nFlags >= 0 && nFlags <= BY_FLAGS.length)
                {
                TypeFit fit = BY_FLAGS[nFlags];
                if (fit != null)
                    {
                    return fit;
                    }
                }

            throw new IllegalStateException("no fit for flag value: " + nFlags);
            }

        /**
         * All of the TypeFit enums, by ordinal.
         */
        private static final TypeFit[] BY_ORDINAL = TypeFit.values();

        /**
         * All of the TypeFit enums, by flags.
         */
        private static final TypeFit[] BY_FLAGS = new TypeFit[0b10000];
        static
            {
            for (TypeFit fit : BY_ORDINAL)
                {
                BY_FLAGS[fit.FLAGS] = fit;
                }
            }

        public static final int FITS     = 0b0001;
        public static final int CONVERTS = 0b0010;
        public static final int PACKS    = 0b0100;
        public static final int UNPACKS  = 0b1000;

        /**
         * Represents the state of the TypeFit.
         */
        public final int FLAGS;
        }

    /**
     * Represents the form that the Expression can or does yield a tuple:
     * <ul>
     * <li>{@code Rejected} - the expression must <b>not</b> yield the requested type(s) in a tuple
     *                        form</li>
     * <li>{@code Accepted} - the expression should yield a tuple if not yielding a tuple would
     *                        involve additional cost</li>
     * <li>{@code Desired}  - the expression should yield a tuple if it can do with no cost</li>
     * <li>{@code Required} - the expression must <b>always</b> yields a tuple of the requested
     *                        type(s)</li>
     * </ul>
     */
    // REVIEW could it simplify to: Never, Either, Always?
    public enum TuplePref {Rejected, Accepted, Desired, Required}

    /**
     * (Pre-validation) Determine if the expression can yield the specified type. Note that if a
     * caller wants to test for a potential tuple result when the tuple would contain more than one
     * value, it must <b>not</b> call this method with a tuple type, but instead it should use the
     * {@link #testFitMulti} method asking for the individual types with the correct TuplePref
     * specified. (A tuple type passed to this method will always return a tuple, and may even
     * result in a tuple inside a tuple, depending on the TuplePref.)
     * <p/>
     * This method should be overridden by any Expression type that only expects to result in a
     * single value.
     *
     * @param ctx           the compilation context for the statement
     * @param typeRequired  the type that the expression is being asked if it can provide
     * @param pref          the TuplePref defining how the caller wants the result
     *
     * @return a TypeFit value describing the expression's capability (or lack thereof) to produce
     *         the required type
     */
    public TypeFit testFit(Context ctx, TypeConstant typeRequired, TuplePref pref)
        {
        checkDepth();

        return testFitMulti(ctx, new TypeConstant[] {typeRequired}, pref);
        }

    /**
     * (Pre-validation) Determine if the expression can yield the specified types.
     * <p/>
     * This method must be overridden by any Expression type that expects to result in multiple
     * values.
     *
     * @param ctx            the compilation context for the statement
     * @param atypeRequired  the types that the expression is being asked if it can provide
     * @param pref           the TuplePref defining how the caller wants the result
     *
     * @return a TypeFit value describing the expression's capability (or lack thereof) to produce
     *         the required type(s)
     */
    public TypeFit testFitMulti(Context ctx, TypeConstant[] atypeRequired, TuplePref pref)
        {
        checkDepth();

        switch (atypeRequired.length)
            {
            case 0:
                // all expressions are required to be able to yield a Void result
                return TypeFit.Fit;

            case 1:
                return testFit(ctx, atypeRequired[0], pref);

            default:
                // anything that can yield separate values must override this method
                return TypeFit.NoFit;
            }
        }

    /**
     * Helper for testFit() and validate() methods.
     *
     * @param ctx      the compilation context for the statement
     * @param typeIn   the type being tested for fit
     * @param typeOut  the type that the expression is being asked if it can provide
     * @param pref     the TuplePref defining how the caller wants the result
     *
     * @return a TypeFit value describing the ability (or lack thereof) to produce the required type
     *         from the specified type
     */
    protected TypeFit calcFit(Context ctx, TypeConstant typeIn, TypeConstant typeOut, TuplePref pref)
        {
        // there are two simple cases to consider:
        // 1) it is always a fit for an expression to go "to void"
        // 2) the most common / desired case is that the type-in is compatible with the type-out
        if (typeOut == null || typeOut.isVoid() || typeIn.isA(typeOut))
            {
            return pref == TuplePref.Required
                    ? TypeFit.Pack
                    : TypeFit.Fit;
            }

        // check for the existence of an @Auto conversion
        if (typeIn.ensureTypeInfo().findConversion(typeOut) != null)
            {
            return pref == TuplePref.Required
                    ? TypeFit.ConvPack
                    : TypeFit.Conv;
            }

        return TypeFit.NoFit;
        }

    /**
     * Helper for testFit() and validate() methods.
     *
     * @param ctx       the compilation context for the statement
     * @param atypeIn   the type(s) being tested for fit
     * @param atypeOut  the types that the expression is being asked if it can provide
     * @param pref      the TuplePref defining how the caller wants the result;
     *
     * @return a TypeFit value describing the ability (or lack thereof) to produce the required type
     *         from the specified type
     */
    protected TypeFit calcFitMulti(Context ctx, TypeConstant[] atypeIn, TypeConstant[] atypeOut, TuplePref pref)
        {
        int cTypesIn  = atypeIn.length;
        int cTypesOut = atypeOut.length;
        if (cTypesIn == 1 && cTypesOut <= 1)
            {
            return calcFit(ctx, atypeIn[0], cTypesOut == 0 ? null : atypeOut[0], pref);
            }

        if (cTypesIn < cTypesOut)
            {
            return TypeFit.NoFit;
            }

        TypeFit fitOut = TypeFit.Fit;
        for (int i = 0; i < cTypesOut; ++i)
            {
            TypeConstant typeIn    = atypeIn [i];
            TypeConstant typeOut   = atypeOut[i];
            TypeFit      fitSingle = calcFit(ctx, typeIn, typeOut, TuplePref.Rejected);
            if (!fitOut.isFit())
                {
                return TypeFit.NoFit;
                }

            fitOut = fitOut.combineWith(fitSingle);
            }

        return pref == TuplePref.Required
                ? fitOut.addPack()
                : fitOut;
        }

    /**
     * Given the specified required type for the expression, resolve names, values, verify definite
     * assignment, etc.
     * <p/>
     * This method transitions the expression from "pre-validated" to "validated".
     * <p/>
     * This method should be overridden by any Expression type that only expects to result in a
     * single value.
     *
     * @param ctx           the compilation context for the statement
     * @param typeRequired  the type that the expression is expected to be able to provide, or null
     *                      if no particular type is expected (which requires the expression to
     *                      settle on a type on its own)
     * @param tuplepref     indicates what options the Expression has in terms of resulting in a
     *                      tuple
     * @param errs          the error listener to log to
     *
     * @return the resulting expression (typically this), or null if compilation cannot proceed
     */
    protected Expression validate(Context ctx, TypeConstant typeRequired, TuplePref tuplepref, ErrorListener errs)
        {
        checkDepth();

        return validateMulti(ctx, new TypeConstant[] {typeRequired}, tuplepref, errs);
        }

    /**
     * Given the specified required type(s) for the expression, resolve names, values, verify
     * definite assignment, etc.
     * <p/>
     * This method transitions the expression from "pre-validated" to "validated".
     * <p/>
     * This method must be overridden by any Expression type that expects to result in multiple
     * values.
     *
     * @param ctx            the compilation context for the statement
     * @param atypeRequired  an array of required types
     * @param tuplepref      indicates what options the Expression has in terms of resulting in a
     *                       tuple
     * @param errs           the error listener to log to
     *
     * @return the resulting expression (typically this), or null if compilation cannot proceed
     */
    protected Expression validateMulti(Context ctx, TypeConstant[] atypeRequired, TuplePref tuplepref, ErrorListener errs)
        {
        checkDepth();

        switch (atypeRequired.length)
            {
            case 0:
                return validate(ctx, pool().typeVoid(), tuplepref, errs);

            case 1:
                // single type expected
                return validate(ctx, atypeRequired[0], tuplepref, errs);

            default:
                // log the error, but allow validation to continue (with no particular
                // expected type) so that we get as many errors exposed as possible in the
                // validate phase
                log(errs, Severity.ERROR, Compiler.WRONG_TYPE_ARITY,
                        atypeRequired.length, 1);
                finishValidation(TypeFit.Fit, atypeRequired, null);
                return null;
            }
        }

    /**
     * Store the result of validating the Expression.
     *
     * @param fit       the fit of that type that was determined by the validation
     * @param type      the single type that results from the Expression
     * @param constVal  a constant value, iff this expression is constant
     */
    protected void finishValidation(TypeFit fit, TypeConstant type, Constant constVal)
        {
        finishValidation(
                fit,
                type == null ? null : new TypeConstant[] {type},
                constVal == null ? null : new Constant[] {constVal});
        }

    /**
     * Store the result of validating the Expression.
     *
     * @param fit        the fit of those types that was determined by the validation
     * @param aType      the types that result from the Expression
     * @param aconstVal  an array of constant values, equal in length to the array of types, iff
     *                   this expression is constant
     */
    protected void finishValidation(TypeFit fit, TypeConstant[] aType, Constant[] aconstVal)
        {
        if (aType == null)
            {
            aType = TypeConstant.NO_TYPES;
            }

        if (m_aType != null)
            {
            throw new IllegalStateException("Expression has already been validated: " + this);
            }

        assert checkElementsNonNull(aType);
        assert aconstVal == null ||
                (aconstVal.length == aType.length && checkElementsNonNull(aconstVal));

        m_fit    = fit == null ? TypeFit.Fit : fit;
        m_aType  = aType;
        m_aConst = aconstVal;
        }

    /**
     * @return true iff the Expression has been validated
     */
    public boolean isValidated()
        {
        return m_aType != null;
        }

    /**
     * Throw an exception if the Expression has not been validated
     */
    protected void checkValidated()
        {
        if (!isValidated())
            {
            throw new IllegalStateException("Expression has not been validated: " + this);
            }
        }

    /**
     * (Post-validation) Determine the number of values represented by the expression.
     * <ul>
     * <li>A {@code Void} expression represents no values</li>
     * <li>An {@link #isSingle() isSingle()==true} expression represents exactly one value (most
     *     common)</li>
     * <li>A multi-value expression represents more than one value</li>
     * </ul>
     * <p/>
     * This method must be overridden by any expression that represents any number of values other
     * than one, or that could be composed of other expressions in such a way that the result is
     * that this expression could represent a number of values other than one.
     *
     * @return the number of values represented by the expression
     */
    public int getValueCount()
        {
        checkValidated();

        return m_aType.length;
        }

    /**
     * (Post-validation) Determine if the Expression represents no resulting value.
     *
     * @return true iff the Expression represents a "void" expression
     */
    public boolean isVoid()
        {
        return getValueCount() == 0;
        }

    /**
     * (Post-validation) Determine if the Expression represents exactly one value.
     *
     * @return true iff the Expression represents exactly one value
     */
    public boolean isSingle()
        {
        return getValueCount() == 1;
        }

    // REVIEW who needs this method?
//    /**
//     * (Post-validation) Determine if the expression represents a {@code conditional} result. A
//     * conditional result is one in which there are multiple results, the first of which is a
//     * boolean, and the remainder of which cannot be safely accessed if the runtime value of that
//     * first boolean is {@code false}.
//     * <p/>
//     * This method must be overridden by any expression that represents or could represent a
//     * conditional result, including as the result of composition of other expressions that could
//     * represent a conditional result.
//     *
//     * @return true iff the Expression represents a conditional value
//     */
//    public boolean isConditional()
//        {
//        return false;
//        }

    /**
     * @return the TypeFit that was determined during validation
     */
    public TypeFit getTypeFit()
        {
        checkValidated();

        return m_fit;
        }

    /**
     * (Post-validation) Determine the type of the expression. For a multi-value expression, the
     * first TypeConstant is returned. For a void expression, the result is null.
     *
     * @return the type of the validated Expression, which is null for a Expression that yields a
     *         Void result, otherwise the type of the <i>first</i> (and typically <i>only</i>) value
     *         resulting from the Expression
     */
    public TypeConstant getType()
        {
        checkValidated();

        TypeConstant[] atype = m_aType;
        return atype.length == 0
                ? null
                : atype[0];
        }

    /**
     * (Post-validation) Obtain an array of types, one for each value that this expression yields.
     * For a void expression, the result is a zero-length array.
     *
     * @return the types of the multiple values yielded by the expression; a zero-length array
     *         indicates a Void type
     */
    public TypeConstant[] getTypes()
        {
        checkValidated();

        return getTypes();
        }

    /**
     * (Post-validation) Determine if the expression represents an L-Value, which means that this expression can be
     * assigned to.
     * <p/>
     * This method must be overridden by any expression that represents an L-Value, or that could
     * be composed of other expressions such that the result represents an L-Value.
     *
     * @return true iff the Expression represents an "L-value" to which a value can be assigned
     */
    public boolean isAssignable()
        {
        return false;
        }

    /**
     * (Post-validation) Determine if the expression aborts.
     * <p/>
     * This method must be overridden by any expression does not complete, or that contains
     * another expression that may not be completable.
     *
     * @return true iff the expression is capable of completing normally
     */
    public boolean isAborting()
        {
        return false;
        }

    /**
     * (Post-validation) Determine if the expression can short-circuit.
     * <p/>
     * This method must be overridden by any expression can short circuit, or any expression that
     * can short circuit as a result of containing another expression that may short-circuit.
     *
     * @return true iff the expression is capable of short-circuiting
     */
    public boolean isShortCircuiting()
        {
        return false;
        }

    /**
     * (Post-validation) Determine if the expression is constant that can be fully resolved and
     * calculated at compile time.
     *
     * @return true iff the Expression is a constant value that is representable by a constant in
     *         the ConstantPool
     */
    public boolean isConstant()
        {
        return m_aConst != null;
        }

    /**
     * (Post-validation) For a expression that provides a compile-time constant, create a constant
     * representations of the value. If {@link #isConstant()} returns true, then the type
     * of the constant will match the result of {@link #getType()}; otherwise there is no such
     * guarantee.
     * <p/>
     * If the exception has more than one value, then this will return a constant tuple of those
     * constant values.
     * <p/>
     * An exception is thrown if the expression is not capable of producing a compile-time constant.
     *
     * @return the constant value of the expression
     */
    public Constant toConstant()
        {
        if (!isConstant())
            {
            throw new IllegalStateException();
            }

        return isVoid()
                ? null
                : toConstants()[0];
        }

    /**
     * (Post-validation) For a expression that provides compile-time constants, obtain a constant
     * representations of the values. If {@link #isConstant()} returns true, then the
     * types of the constant values will match the result of {@link #getTypes()}.
     * <p/>
     * An exception is thrown if the expression does not producing a compile-time constant.
     *
     * @return the compile-times constant values of the expression
     */
    public Constant[] toConstants()
        {
        if (!isConstant())
            {
            throw new IllegalStateException();
            }

        return m_aConst;
        }

    // REVIEW - could values of "const" form (requiring one-time instantiation/initialization at runtime), that are NOT ConstantPool Constant values, be used e.g. for a CASE statement?

    /**
     * (Post-validation) Generate an argument that represents the result of this expression.
     * <p/>
     * This method (or the plural version) must be overridden by any expression that is not always
     * constant.
     *
     * @param code   the code block
     * @param fPack  true if the result must be delivered wrapped in a tuple
     * @param errs   the error list to log any errors to
     *
     * @return a resulting argument of the specified type, or of a tuple of the specified type if
     *         that is both allowed and "free" to produce
     */
    public Argument generateArgument(Code code, boolean fPack, ErrorListener errs)
        {
        checkDepth();
        assert !isVoid();

        if (isConstant())
            {
            return fPack
                    ? pool().ensureTupleConstant(pool().ensureParameterizedTypeConstant(
                            pool().typeTuple(), getTypes()), toConstants())
                    : toConstant();
            }

        return generateArguments(code, fPack, errs)[0];
        }

    /**
     * Generate arguments of the specified types for this expression, or generate an error if that
     * is not possible.
     * <p/>
     * This method must be overridden by any expression that is multi-value-aware.
     *
     * @param code   the code block
     * @param fPack  true if the result must be delivered wrapped in a tuple
     * @param errs   the error list to log any errors to
     *
     * @return an array of resulting arguments, which will either be the same length as the value
     *         count of the expression, or length 1 for a tuple result iff fPack is true
     */
    public Argument[] generateArguments(Code code, boolean fPack, ErrorListener errs)
        {
        checkDepth();

        if (isConstant())
            {
            return fPack
                    ? new Argument[]
                            {
                            pool().ensureTupleConstant(pool().ensureParameterizedTypeConstant(
                                    pool().typeTuple(), getTypes()), toConstants())
                            }
                    : toConstants();
            }

        TypeConstant[] atype = getTypes();
        switch (atype.length)
            {
            case 0:
                // Void means that the results of the expression are black-holed
                generateAssignments(code, NO_LVALUES, errs);
                return NO_RVALUES;

            case 1:
                return new Argument[] {generateArgument(code, fPack, errs)};

            default:
                // this must be overridden
                throw notImplemented();
            }
        }

    /**
     * For an L-Value expression with exactly one value, create a representation of the L-Value.
     * <p/>
     * An exception is generated if the expression is not assignable.
     * <p/>
     * This method must be overridden by any expression that is assignable, unless the multi-value
     * version of this method is overridden instead.
     *
     * @param code  the code block
     * @param errs  the error list to log any errors to
     *
     * @return an Assignable object
     */
    public Assignable generateAssignable(Code code, ErrorListener errs)
        {
        checkDepth();

        if (!isAssignable() || isVoid())
            {
            throw new IllegalStateException();
            }

        return generateAssignables(code, errs)[0];
        }

    /**
     * For an L-Value expression, create representations of the L-Values.
     * <p/>
     * An exception is generated if the expression is not assignable.
     * <p/>
     * This method must be overridden by any expression that is assignable and multi-value-aware.
     *
     * @param code  the code block
     * @param errs  the error list to log any errors to
     *
     * @return an array of {@link #getValueCount()} Assignable objects
     */
    public Assignable[] generateAssignables(Code code, ErrorListener errs)
        {
        checkDepth();

        if (!isAssignable())
            {
            throw new IllegalStateException();
            }

        switch (getValueCount())
            {
            case 0:
                generateVoid(code, errs);
                return NO_LVALUES;

            case 1:
                return new Assignable[] {generateAssignable(code, errs)};

            default:
                log(errs, Severity.ERROR, Compiler.WRONG_TYPE_ARITY, 1, getValueCount());
                return NO_LVALUES;
            }
        }

    /**
     * Generate the necessary code that discards the value of this expression.
     * <p/>
     * This method should be overridden by any expression that can produce better code than the
     * default discarded-assignment code.
     *
     * @param code  the code block
     * @param errs  the error list to log any errors to
     */
    public void generateVoid(Code code, ErrorListener errs)
        {
        checkDepth();

        if (!isConstant() && !isVoid())
            {
            if (isSingle())
                {
                generateAssignment(code, new Assignable(), errs);
                }
            else
                {
                Assignable[] asnVoid = new Assignable[getValueCount()];
                Arrays.fill(asnVoid, new Assignable());
                generateAssignments(code, asnVoid, errs);
                }
            }
        }

    /**
     * Generate the necessary code that assigns the value of this expression to the specified
     * L-Value, or generate an error if that is not possible.
     * <p/>
     * This method should be overridden by any expression that can produce better code than the
     * default assignment code.
     *
     * @param code  the code block
     * @param LVal  the Assignable object representing the L-Value
     * @param errs  the error list to log any errors to
     */
    public void generateAssignment(Code code, Assignable LVal, ErrorListener errs)
        {
        checkDepth();

        if (isSingle())
            {
            // this will be overridden by classes that can push down the work
            Argument arg = generateArgument(code, false, errs);
            LVal.assign(arg, code, errs);
            }
        else
            {
            generateAssignments(code, new Assignable[] {LVal}, errs);
            }
        }

    /**
     * Generate the necessary code that assigns the values of this expression to the specified
     * L-Values, or generate an error if that is not possible.
     * <p/>
     * This method should be overridden by any expression that must support multi-values and can
     * produce better code than the default assignment code.
     *
     * @param code   the code block
     * @param aLVal  an array of Assignable objects representing the L-Values
     * @param errs   the error list to log any errors to
     */
    public void generateAssignments(Code code, Assignable[] aLVal, ErrorListener errs)
        {
        checkDepth();

        int cLVals = aLVal.length;
        int cRVals = getValueCount();
        assert cLVals <= cRVals;
        if (cLVals < cRVals)
            {
            // blackhole the missing LVals
            Assignable[] aLValNew = new Assignable[cRVals];
            Arrays.fill(aLValNew, new Assignable());
            System.arraycopy(aLVal, 0, aLValNew, 0, cLVals);
            aLVal  = aLValNew;
            cLVals = cRVals;
            }

        switch (cLVals)
            {
            case 0:
                if (!m_fInAssignment)
                    {
                    m_fInAssignment = true;
                    generateVoid(code, errs);
                    m_fInAssignment = false;
                    }
                break;

            case 1:
                if (!m_fInAssignment)
                    {
                    m_fInAssignment = true;
                    generateAssignment(code, aLVal[0], errs);
                    m_fInAssignment = false;
                    break;
                    }
                // fall through

            default:
                Argument[] aArg = generateArguments(code, false, errs);
                for (int i = 0; i < cLVals; ++i)
                    {
                    aLVal[i].assign(aArg[i], code, errs);
                    }
                break;
            }
        }

    /**
     * Generate the necessary code that jumps to the specified label if this expression evaluates
     * to the boolean value indicated in <tt>fWhenTrue</tt>.
     *
     * @param code       the code block
     * @param label      the label to conditionally jump to
     * @param fWhenTrue  indicates whether to jump when this expression evaluates to true, or
     *                   whether to jump when this expression evaluates to false
     * @param errs       the error list to log any errors to
     */
    public void generateConditionalJump(Code code, Label label, boolean fWhenTrue,
            ErrorListener errs)
        {
        checkDepth();

        assert !isVoid() && getType().isA(pool().typeBoolean());

        // this is just a generic implementation; sub-classes should override this simplify the
        // generated code (e.g. by not having to always generate a separate boolean value)
        Argument arg = generateArgument(code, false, errs);
        code.add(fWhenTrue
                ? new JumpTrue(arg, label)
                : new JumpFalse(arg, label));
        }


    // ----- helpers -------------------------------------------------------------------------------

    /**
     * Determine if this expression can generate an argument of the specified type, or that can be
     * assigned to the specified type.
     *
     * @param typeThat  an argument type
     *
     * @return true iff this expression can be rendered as the specified argument type
     */
    public boolean isAssignableTo(TypeConstant typeThat)
        {
        TypeConstant typeImplicit = getType();
        return typeImplicit.isA(typeThat)
                || typeImplicit.ensureTypeInfo().findConversion(typeThat) != null;
        }

    /**
     * @return true iff the Expression is of the type "Boolean"
     */
    public boolean isTypeBoolean()
        {
        return getType().isEcstasy("Boolean") || isAssignableTo(pool().typeBoolean());
        }

    /**
     * @return true iff the Expression is the constant value "false"
     */
    public boolean isConstantFalse()
        {
        return isConstant() && toConstant().equals(pool().valFalse());
        }

    /**
     * @return true iff the Expression is the constant value "false"
     */
    public boolean isConstantTrue()
        {
        return isConstant() && toConstant().equals(pool().valTrue());
        }

    /**
     * @return true iff the Expression is the constant value "Null"
     */
    public boolean isConstantNull()
        {
        return isConstant() && toConstant().equals(pool().valNull());
        }

    /**
     * Given an constant, verify that it can be assigned to (or somehow converted to) the specified
     * type, and do so.
     *
     * @param constIn  the constant that needs to be validated as assignable
     * @param typeOut  the type that the constant must be assignable to
     * @param errs     the error list to log any errors to, for example if the constant cannot be
     *                 coerced in a manner to make it assignable
     *
     * @return the constant to use
     */
    protected Constant validateAndConvertConstant(Constant constIn, TypeConstant typeOut,
            ErrorListener errs)
        {
        TypeConstant typeIn = constIn.getType();
        if (typeIn.isA(typeOut))
            {
            // common case; no conversion is necessary
            return constIn;
            }

        Constant constOut;
        try
            {
            constOut = constIn.convertTo(typeOut);
            }
        catch (ArithmeticException e)
            {
            // conversion failure due to range etc.
            log(errs, Severity.ERROR, Compiler.VALUE_OUT_OF_RANGE, typeOut,
                    constIn.getValueString());
            return generateFakeConstant(typeOut);
            }

        if (constOut == null)
            {
            // conversion apparently was not possible
            log(errs, Severity.ERROR, Compiler.WRONG_TYPE, typeOut, typeIn);
            constOut = generateFakeConstant(typeOut);
            }

        return constOut;
        }

    /**
     * Given an argument, verify that it can be assigned to (or somehow converted to) the specified
     * type, and do so.
     *
     * @param argIn    the argument that needs to be validated as assignable
     * @param code     the code block
     * @param typeOut  the type that the argument must be assignable to
     * @param errs     the error list to log any errors to, for example if the object cannot be
     *                 coerced in a manner to make it assignable
     *
     * @return the argument to use
     */
    protected Argument validateAndConvertSingle(Argument argIn, Code code, TypeConstant typeOut, ErrorListener errs)
        {
        // assume that the result is the same as what was passed in
        Argument argOut = argIn;

        TypeConstant typeIn = argIn.getRefType();
        if (!typeIn.equals(typeOut) && !typeIn.isA(typeOut))
            {
            MethodConstant constConv = typeIn.ensureTypeInfo().findConversion(typeOut);
            if (constConv == null)
                {
                log(errs, Severity.ERROR, Compiler.WRONG_TYPE, typeOut, typeIn);
                }
            else
                {
                argOut = new Register(typeOut);
                code.add(new Invoke_01(argIn, constConv, argOut));
                }
            }

        return argOut;
        }

    /**
     * When a register is needed to store a value that is never used, the "black hole" register is
     * used. It is considered a "write only" register. This is also useful during compilation, when
     * an expression cannot yield an actual argument; the expression should log an error, and return
     * a black hole register instead (which will serve as a natural assertion later in the assembly
     * cycle, in case someone forgets to log an error).
     *
     * @param type  the type of the register
     *
     * @return a black hole register of the specified type
     */
    protected Register generateBlackHole(TypeConstant type)
        {
        return new Register(type, Op.A_IGNORE);
        }

    /**
     * When an error occurs during compilation, but a constant of a specific type is required, this
     * method comes to the rescue.
     *
     * @param type  the type of the constant
     *
     * @return a constant of the specified type
     */
    protected Constant generateFakeConstant(TypeConstant type)
        {
        return Constant.defaultValue(type);
        }

    /**
     * Temporary to prevent stack overflow from methods that haven't yet been overridden.
     *
     * @throws UnsupportedOperationException if it appears that there is an infinite loop
     */
    protected void checkDepth()
        {
        if (++m_cDepth > 40)
            {
            throw notImplemented();
            }
        }


    // ----- inner class: Assignable ---------------------------------------------------------------


    /**
     * Assignable represents an L-Value.
     */
    public class Assignable
        {
        // ----- constructors ------------------------------------------------------------------

        /**
         * Construct a black hole L-Value.
         */
        public Assignable()
            {
            m_nForm = BlackHole;
            }

        /**
         * Construct an Assignable based on a local variable.
         *
         * @param regVar  the Register, representing the local variable
         */
        public Assignable(Register regVar)
            {
            m_nForm = LocalVar;
            m_reg = regVar;
            }

        /**
         * Construct an Assignable based on a property (either local or "this").
         *
         * @param regTarget  the register, representing the property target
         * @param constProp  the PropertyConstant
         */
        public Assignable(Register regTarget, PropertyConstant constProp)
            {
            m_nForm = regTarget.getIndex() == Op.A_TARGET ? LocalProp : TargetProp;
            m_reg = regTarget;
            m_prop = constProp;
            }

        /**
         * Construct an Assignable based on a single dimension array local variable.
         *
         * @param argArray  the Register, representing the local variable holding an array
         * @param index     the index into the array
         */
        public Assignable(Register argArray, Argument index)
            {
            m_nForm = Indexed;
            m_reg = argArray;
            m_oIndex = index;
            }

        /**
         * Construct an Assignable based on a multi (any) dimension array local variable.
         *
         * @param regArray  the Register, representing the local variable holding an array
         * @param indexes   an array of indexes into the array
         */
        public Assignable(Register regArray, Argument[] indexes)
            {
            assert indexes != null && indexes.length > 0;

            m_nForm = indexes.length == 1 ? Indexed : IndexedN;
            m_reg = regArray;
            m_oIndex = indexes.length == 1 ? indexes[0] : indexes;
            }

        /**
         * Construct an Assignable based on a local property that is a single dimension array.
         *
         * @param argArray  the Register, representing the local variable holding an array
         * @param index     the index into the array
         */
        public Assignable(Register argArray, PropertyConstant constProp, Argument index)
            {
            m_nForm = IndexedProp;
            m_reg = argArray;
            m_prop = constProp;
            m_oIndex = index;
            }

        /**
         * Construct an Assignable based on a local property that is a a multi (any) dimension array.
         *
         * @param regArray  the Register, representing the local variable holding an array
         * @param indexes   an array of indexes into the array
         */
        public Assignable(Register regArray, PropertyConstant constProp, Argument[] indexes)
            {
            assert indexes != null && indexes.length > 0;

            m_nForm = indexes.length == 1 ? IndexedProp : IndexedNProp;
            m_reg = regArray;
            m_prop = constProp;
            m_oIndex = indexes.length == 1 ? indexes[0] : indexes;
            }

        // ----- accessors ---------------------------------------------------------------------

        /**
         * @return the type of the L-Value
         */
        public TypeConstant getType()
            {
            switch (m_nForm)
                {
                case BlackHole:
                    return pool().typeObject();

                case LocalVar:
                    return getRegister().getRefType();

                case LocalProp:
                case TargetProp:
                case IndexedProp:
                case IndexedNProp:
                    return getProperty().getRefType();

                case Indexed:
                case IndexedN:
                    return getArray().getRefType();

                default:
                    throw new IllegalStateException();
                }
            }

        /**
         * Determine the type of assignability:
         * <ul>
         * <li>{@link #BlackHole} - a write-only register that anyone can assign to, resulting in
         *     the value being discarded</li>
         * <li>{@link #LocalVar} - a local variable of a method that can be assigned</li>
         * <li>{@link #LocalProp} - a local (this:private) property that can be assigned</li>
         * <li>{@link #TargetProp} - a property of a specified reference that can be assigned</li>
         * <li>{@link #Indexed} - an index into a single-dimensioned array</li>
         * <li>{@link #IndexedN} - an index into a multi-dimensioned array</li>
         * <li>{@link #IndexedProp} - an index into a single-dimensioned array property</li>
         * <li>{@link #IndexedNProp} - an index into a multi-dimensioned array property</li>
         * </ul>
         *
         * @return the form of the Assignable, one of: {@link #BlackHole}, {@link #LocalVar},
         *         {@link #LocalProp}, {@link #TargetProp}, {@link #Indexed}, {@link #IndexedN}
         */
        public int getForm()
            {
            return m_nForm;
            }

        /**
         * @return the register, iff this Assignable represents a local variable
         */
        public Register getRegister()
            {
            if (m_nForm != LocalVar)
                {
                throw new IllegalStateException();
                }
            return m_reg;
            }

        /**
         * @return true iff the lvalue is a register for a LocalVar, the property constant for a
         *         LocalProp, or the black-hole register for a BlackHole
         */
        public boolean isLocalArgument()
            {
            switch (m_nForm)
                {
                case BlackHole:
                case LocalVar:
                case LocalProp:
                    return true;

                default:
                    return false;
                }
            }

        /**
         * @return the register for a LocalVar, the property constant for a LocalProp, or the
         *         black-hole register for a BlackHole
         */
        public Argument getLocalArgument()
            {
            switch (m_nForm)
                {
                case BlackHole:
                    return new Register(pool().typeObject(), Op.A_IGNORE);

                case LocalVar:
                    return getRegister();

                case LocalProp:
                    return getProperty();

                default:
                    throw new IllegalStateException();
                }
            }

        /**
         * @return the property target, iff this Assignable represents a property
         */
        public Argument getTarget()
            {
            if (m_nForm != LocalProp && m_nForm != TargetProp)
                {
                throw new IllegalStateException();
                }
            return m_reg;
            }

        /**
         * @return the property, iff this Assignable represents a property
         */
        public PropertyConstant getProperty()
            {
            if (m_nForm != LocalProp && m_nForm != TargetProp)
                {
                throw new IllegalStateException();
                }
            return m_prop;
            }

        /**
         * @return the argument for the array, iff this Assignable represents an array
         */
        public Argument getArray()
            {
            if (m_nForm != Indexed && m_nForm != IndexedN)
                {
                throw new IllegalStateException();
                }
            return m_reg;
            }

        /**
         * @return the array index, iff this Assignable represents a 1-dimensional array
         */
        public Argument getIndex()
            {
            if (m_nForm == Indexed || m_nForm == IndexedProp)
                {
                return (Argument) m_oIndex;
                }

            throw new IllegalStateException();
            }

        /**
         * @return the array indexes, iff this Assignable represents an any-dimensional array
         */
        public Argument[] getIndexes()
            {
            if (m_nForm == Indexed || m_nForm == IndexedProp)
                {
                return new Argument[] {(Argument) m_oIndex};
                }

            if (m_nForm == IndexedN || m_nForm == IndexedNProp)
                {
                return (Argument[]) m_oIndex;
                }

            throw new IllegalStateException();
            }

        // ----- compilation -------------------------------------------------------------------

        /**
         * Generate the assignment-specific assembly code.
         *
         * @param arg   the Argument, representing the R-value
         * @param code  the code object to which the assembly is added
         * @param errs  the error listener to log to
         */
        public void assign(Argument arg, Code code, ErrorListener errs)
            {
            switch (m_nForm)
                {
                case BlackHole:
                    break;

                case LocalVar:
                    code.add(new Move(arg, getRegister()));
                    break;

                case LocalProp:
                    code.add(new L_Set(getProperty(), arg));
                    break;

                case TargetProp:
                    code.add(new P_Set(getProperty(), getTarget(), arg));
                    break;

                case Indexed:
                    code.add(new I_Set(getArray(), getIndex(), arg));
                    break;

                case IndexedN:
                    throw notImplemented();

                case IndexedProp:
                    code.add(new I_Set(getProperty(), getIndex(), arg));
                    break;

                case IndexedNProp:
                    throw notImplemented();

                default:
                    throw new IllegalStateException();
                }
            }

        // ----- fields ------------------------------------------------------------------------

        public static final int BlackHole    = 0;
        public static final int LocalVar     = 1;
        public static final int LocalProp    = 2;
        public static final int TargetProp   = 3;
        public static final int Indexed      = 4;
        public static final int IndexedN     = 5;
        public static final int IndexedProp  = 6;
        public static final int IndexedNProp = 7;

        private int              m_nForm;
        private Register         m_reg;
        private PropertyConstant m_prop;
        private Object           m_oIndex;
        }


    // ----- fields --------------------------------------------------------------------------------

    public static final Assignable[] NO_LVALUES = new Assignable[0];
    public static final Argument[]   NO_RVALUES = new Argument[0];

    /**
     * After validation, contains the TypeFit determined during the validation.
     */
    private TypeFit m_fit;

    /**
     * After validation, contains the type(s) of the expression.
     */
    private TypeConstant[] m_aType;

    /**
     * After validation, contains the constant value(s) of the expression, iff the expression is a
     * constant.
     */
    private Constant[] m_aConst;

    /**
     * This allows a sub-class to not override either generateAssignment() method, by having a
     * relatively inefficient and/or non-effective implementation being provided by default (without
     * infinite recursion), or alternatively implementing one and/or the other of the two methods.
     */
    private transient boolean m_fInAssignment;

    /**
     * (Temporary) Infinite recursion prevention.
     */
    private int m_cDepth;
    }
