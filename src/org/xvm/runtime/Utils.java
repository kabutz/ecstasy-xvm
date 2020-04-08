package org.xvm.runtime;


import java.sql.Timestamp;

import java.util.ArrayList;
import java.util.List;

import java.util.concurrent.CompletableFuture;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Component.Format;
import org.xvm.asm.Constant;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;
import org.xvm.asm.PropertyStructure;

import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.PropertyConstant;
import org.xvm.asm.constants.SingletonConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.ObjectHandle.DeferredCallHandle;

import org.xvm.runtime.template.xBoolean;
import org.xvm.runtime.template.xEnum;
import org.xvm.runtime.template.xEnum.EnumHandle;
import org.xvm.runtime.template.xModule;
import org.xvm.runtime.template.xOrdered;
import org.xvm.runtime.template.xPackage;
import org.xvm.runtime.template.xRef.RefHandle;
import org.xvm.runtime.template.xString.StringHandle;

import org.xvm.runtime.template._native.reflect.xRTFunction;
import org.xvm.runtime.template._native.reflect.xRTFunction.FullyBoundHandle;

import org.xvm.runtime.template.annotations.xFutureVar;


/**
 * Various helpers.
 */
public abstract class Utils
    {
    public final static int[] ARGS_NONE = new int[0];
    public final static ObjectHandle[] OBJECTS_NONE = new ObjectHandle[0];

    public static void log(Frame frame, String sMsg)
        {
        if (sMsg.charAt(0) == '\n')
            {
            System.out.println();
            sMsg = sMsg.substring(1);
            }

        ServiceContext ctx;
        long lFiberId;

        if (frame == null)
            {
            ctx = ServiceContext.getCurrentContext();
            lFiberId = -1;
            }
        else
            {
            ctx = frame.f_context;
            lFiberId = frame.f_fiber.getId();
            }

        System.out.println(new Timestamp(System.currentTimeMillis())
            + " " + ctx + ", fiber " + lFiberId + ": " + sMsg);
        }

    /**
     * Ensure that the specified array of arguments is of the specified size.
     *
     * @param ahArg  the array of arguments
     * @param cVars  the desired array size
     *
     * @return the array of the desired size containing all the arguments
     */
    public static ObjectHandle[] ensureSize(ObjectHandle[] ahArg, int cVars)
        {
        int cArgs = ahArg.length;
        if (cArgs == cVars)
            {
            return ahArg;
            }

        if (cArgs < cVars)
            {
            ObjectHandle[] ahVar = new ObjectHandle[cVars];
            System.arraycopy(ahArg, 0, ahVar, 0, cArgs);
            return ahVar;
            }

        throw new IllegalArgumentException("Requested size " + cVars +
            " is less than the array size " + cArgs);
        }

    /**
     * Create a FullyBoundHandle representing a finalizer of the specified constructor.
     *
     * @param constructor  the constructor
     * @param ahArg        the arguments to bind
     *
     * @return a FullyBoundHandle representing the finalizer or null if there is no finalizer
     */
    public static FullyBoundHandle makeFinalizer(MethodStructure constructor, ObjectHandle[] ahArg)
        {
        MethodStructure methodFinally = constructor.getConstructFinally();

        return methodFinally == null
            ? null
            : xRTFunction.makeHandle(methodFinally).bindArguments(ahArg);
        }

    /**
     * Helper method for the "toString()" method invocation that pushes the result onto the frame's
     * stack.
     *
     * @param frame   the current frame
     * @param hValue  the value to get a property for
     *
     * @return one of R_EXCEPTION, R_NEXT or R_CALL values
     */
    public static int callToString(Frame frame, ObjectHandle hValue)
        {
        CallChain chain = hValue.getComposition().getMethodCallChain(frame.poolContext().sigToString());
        return chain.isNative()
            ? hValue.getTemplate().buildStringValue(frame, hValue, Op.A_STACK)
            : chain.invoke(frame, hValue, OBJECTS_NONE, Op.A_STACK);
        }

    /**
     * An adapter method that assigns the result of a natural execution to a calling frame
     * that expects a conditional return.
     *
     * @param frame     the frame that expects a conditional return value
     * @param iResult   the result of the previous execution
     * @param aiReturn  the return indexes for the conditional return
     *
     * @return one of R_EXCEPTION, R_NEXT or R_CALL values
     */
    public static int assignConditionalResult(Frame frame, int iResult, int[] aiReturn)
        {
        switch (iResult)
            {
            case Op.R_NEXT:
                return frame.assignValues(aiReturn, xBoolean.TRUE, frame.popStack());

            case Op.R_CALL:
                frame.m_frameNext.addContinuation(frameCaller ->
                    frameCaller.assignValues(aiReturn, xBoolean.TRUE, frame.popStack()));
                return Op.R_CALL;

            case Op.R_EXCEPTION:
                return Op.R_EXCEPTION;

            default:
                throw new IllegalStateException();
            }
        }

    /**
     * A helper method for native code that needs to assign EnumHandle values retrieved
     * via {@link xEnum#getEnumByName} or {@link xEnum#getEnumByOrdinal}.
     *
     * @param frame  the current frame
     * @param hEnum  the Enum handle
     *
     * @return the initialized (public) enum handle or a deferred handle
     */
    public static ObjectHandle ensureInitializedEnum(Frame frame, EnumHandle hEnum)
        {
        if (hEnum.isStruct())
            {
            // turn the Enum struct into a "public" value
            IdentityConstant idValue = (IdentityConstant) hEnum.getType().getDefiningConstant();
            return frame.getConstHandle(
                    frame.poolContext().ensureSingletonConstConstant(idValue));
            }
        return hEnum;
        }

    /**
     * A helper method for native code that needs to assign EnumHandle values retrieved
     * via {@link xEnum#getEnumByName} or {@link xEnum#getEnumByOrdinal}.
     *
     * @param frame    the current frame
     * @param hEnum    the Enum handle
     * @param iReturn  the register to assign the value into
     *
     * @return one of R_EXCEPTION, R_NEXT or R_CALL values
     */
    public static int assignInitializedEnum(Frame frame, EnumHandle hEnum, int iReturn)
        {
        ObjectHandle hValue = ensureInitializedEnum(frame, hEnum);

        if (Op.isDeferred(hValue))
            {
            ObjectHandle[] ahValue = new ObjectHandle[] {hValue};
            Frame.Continuation stepNext = frameCaller ->
                frameCaller.assignValue(iReturn, ahValue[0]);

            return new GetArguments(ahValue, stepNext).doNext(frame);
            }

        return frame.assignValue(iReturn, hValue);
        }


    // ----- "local property or DeferredCallHandle as an argument" support -----

    public static class GetArguments
                implements Frame.Continuation
        {
        public GetArguments(ObjectHandle[] ahArg, Frame.Continuation continuation)
            {
            this.ahArg = ahArg;
            this.continuation = continuation;
            }

        @Override
        public int proceed(Frame frameCaller)
            {
            updateResult(frameCaller);

            return doNext(frameCaller);
            }

        protected void updateResult(Frame frameCaller)
            {
            // replace a property handle with the value
            ahArg[index] = frameCaller.popStack();
            }

        public int doNext(Frame frameCaller)
            {
            while (++index < ahArg.length)
                {
                ObjectHandle hArg = ahArg[index];
                if (hArg == null)
                    {
                    // nulls can only be at the tail of the array
                    break;
                    }

                if (hArg instanceof DeferredCallHandle)
                    {
                    return ((DeferredCallHandle) hArg).proceed(frameCaller, this);
                    }
                }
            return continuation.proceed(frameCaller);
            }

        private final ObjectHandle[] ahArg;
        private final Frame.Continuation continuation;
        private int index = -1;
        }

    public static class AssignValues
            implements Frame.Continuation
        {
        public AssignValues(int[] aiReturn, ObjectHandle[] ahValue)
            {
            this.aiReturn  = aiReturn;
            this.ahValue   = ahValue;
            }

        public int proceed(Frame frameCaller)
            {
            while (++index < aiReturn.length)
                {
                ObjectHandle hValue = ahValue[index];
                if (hValue instanceof DeferredCallHandle)
                    {
                    DeferredCallHandle hDeferred = ((DeferredCallHandle) hValue);
                    hDeferred.addContinuation(this::updateDeferredValue);
                    return hDeferred.proceed(frameCaller, this);
                    }

                switch (frameCaller.assignValue(aiReturn[index], ahValue[index]))
                    {
                    case Op.R_NEXT:
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalStateException();
                    }
                }

            return Op.R_NEXT;
            }

        protected int updateDeferredValue(Frame frameCaller)
            {
            ahValue[index--] = frameCaller.popStack();
            return Op.R_NEXT;
            }

        private final int[] aiReturn;
        private final ObjectHandle[] ahValue;

        private int index = -1;
        }

    public static class ReturnValues
            implements Frame.Continuation
        {
        public ReturnValues(int[] aiReturn, ObjectHandle[] ahValue, boolean[] afDynamic)
            {
            this.aiReturn  = aiReturn;
            this.ahValue   = ahValue;
            this.afDynamic = afDynamic;
            }

        public int proceed(Frame frameCaller)
            {
            while (++index < aiReturn.length)
                {
                ObjectHandle hValue = ahValue[index];
                if (hValue instanceof DeferredCallHandle)
                    {
                    DeferredCallHandle hDeferred = ((DeferredCallHandle) hValue);
                    hDeferred.addContinuation(this::updateDeferredValue);
                    return hDeferred.proceed(frameCaller, this);
                    }

                switch (frameCaller.returnValue(aiReturn[index], ahValue[index],
                        afDynamic != null && afDynamic[index]))
                    {
                    case Op.R_RETURN:
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_RETURN_EXCEPTION:
                        return Op.R_RETURN_EXCEPTION;

                    default:
                        throw new IllegalStateException();
                    }
                }

            return Op.R_RETURN;
            }

        protected int updateDeferredValue(Frame frameCaller)
            {
            ahValue[index--] = frameCaller.popStack();
            return Op.R_NEXT;
            }

        private final int[] aiReturn;
        private final ObjectHandle[] ahValue;
        private final boolean[] afDynamic;

        private int index = -1;
        }

    public static class ContinuationChain
            implements Frame.Continuation
        {
        public ContinuationChain(Frame.Continuation step0)
            {
            f_list = new ArrayList<>();
            f_list.add(step0);
            }

        public void add(Frame.Continuation stepNext)
            {
            f_list.add(stepNext);
            }

        @Override
        public int proceed(Frame frameCaller)
            {
            while (++index < f_list.size())
                {
                int iResult = f_list.get(index).proceed(frameCaller);
                switch (iResult)
                    {
                    case Op.R_NEXT:
                        continue;

                    case Op.R_CALL:
                        Frame              frameNext = frameCaller.m_frameNext;
                        Frame.Continuation contNext  = frameNext.m_continuation;

                        if (contNext != null)
                            {
                            // the previous continuation caused another call and the callee has
                            // its own continuations; in that case we need to execute those
                            // continuations before continuing with our own chain
                            // (assuming everyone returns normally)
                            f_list.set(index--, contNext);
                            }
                        frameNext.m_continuation = this;
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        assert frameCaller.m_hException != null;
                        return Op.R_EXCEPTION;

                    default:
                        if (iResult >= 0)
                            {
                            // only the very last continuation can return a specific op index
                            // (see OpCondJump)
                            assert index + 1 == f_list.size();
                            return iResult;
                            }
                        throw new IllegalStateException();
                    }
                }
            return Op.R_NEXT;
            }

        private final List<Frame.Continuation> f_list;
        private int index = -1;
        }


    // ----- comparison support -----

    public static int callEqualsSequence(Frame frame, TypeConstant type1, TypeConstant type2,
                                         ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        if (hValue1 == hValue2)
            {
            return frame.assignValue(iReturn, xBoolean.TRUE);
            }

        switch (type1.callEquals(frame, hValue1, hValue2, Op.A_STACK))
            {
            case Op.R_NEXT:
                return completeEquals(frame, type2, hValue1, hValue2, iReturn);

            case Op.R_CALL:
                frame.m_frameNext.addContinuation(frameCaller ->
                    completeEquals(frameCaller, type2, hValue1, hValue2, iReturn));
                return Op.R_CALL;

            case Op.R_EXCEPTION:
                return Op.R_EXCEPTION;

            default:
                throw new IllegalStateException();
            }
        }

    protected static int completeEquals(Frame frame, TypeConstant type2,
                                        ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        ObjectHandle hResult = frame.popStack();
        return hResult == xBoolean.FALSE
            ? frame.assignValue(iReturn, hResult)
            : type2.callEquals(frame, hValue1, hValue2, iReturn);
        }

    public static int callCompareSequence(Frame frame, TypeConstant type1, TypeConstant type2,
                                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        if (hValue1 == hValue2)
            {
            return frame.assignValue(iReturn, xOrdered.EQUAL);
            }

        switch (type1.callCompare(frame, hValue1, hValue2, Op.A_STACK))
            {
            case Op.R_NEXT:
                return completeCompare(frame, type2, hValue1, hValue2, iReturn);

            case Op.R_CALL:
                frame.m_frameNext.addContinuation(frameCaller ->
                    completeCompare(frameCaller, type2, hValue1, hValue2, iReturn));
                return Op.R_CALL;

            case Op.R_EXCEPTION:
                return Op.R_EXCEPTION;

            default:
                throw new IllegalStateException();
            }
        }

    /**
     * Completion of the callCompare implementation.
     */
    protected static int completeCompare(Frame frame, TypeConstant type2,
                                  ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        ObjectHandle hResult = frame.popStack();
        return hResult != xOrdered.EQUAL
            ? frame.assignValue(iReturn, hResult)
            : type2.callCompare(frame, hValue1, hValue2, iReturn);
        }


    // ----- toString support -----

    public static class TupleToString
            implements Frame.Continuation
        {
        public TupleToString(StringBuilder sb, ObjectHandle[] ahValue,
                             String[] asLabel, Frame.Continuation nextStep)
            {
            this.sb       = sb;
            this.ahValue  = ahValue;
            this.asLabel  = asLabel;
            this.nextStep = nextStep;
            }

        public int doNext(Frame frameCaller)
            {
            loop: while (++index < ahValue.length)
                {
                switch (callToString(frameCaller, ahValue[index]))
                    {
                    case Op.R_NEXT:
                        if (updateResult(frameCaller))
                            {
                            continue loop;
                            }
                        else
                            {
                            break loop;
                            }

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalStateException();
                    }
                }

            finishResult();
            return nextStep.proceed(frameCaller);
            }

        @Override
        public int proceed(Frame frameCaller)
            {
            if (updateResult(frameCaller))
                {
                return doNext(frameCaller);
                }

            // too much text; enough for an output...
            return nextStep.proceed(frameCaller);
            }

        // return false if the buffer is full
        protected boolean updateResult(Frame frameCaller)
            {
            StringHandle hString = (StringHandle) frameCaller.popStack();
            String sLabel = asLabel == null ? null : asLabel[index];

            if (sLabel != null)
                {
                sb.append(sLabel).append('=');
                }
            sb.append(hString.getValue());

            if (sb.length() < MAX_LEN)
                {
                sb.append(", ");
                return true;
                }

            sb.append("...");
            return false;
            }

        protected void finishResult()
            {
            if (sb.length() >= 2 && sb.charAt(sb.length() - 2) == ',')
                {
                sb.setLength(sb.length() - 2); // remove the trailing ", "
                sb.append(')');
                }
            }

        protected static final int MAX_LEN = 16*1024;

        protected final StringBuilder      sb;
        protected final ObjectHandle[]     ahValue;
        protected final String[]           asLabel;
        protected final Frame.Continuation nextStep;

        protected int index = -1;
        }

    // ----- various run-time support -----

    /**
     * Ensure that all SingletonConstants in the specified list are initialized and proceed
     * with the specified continuation.
     *
     * @param frame           the caller's frame
     * @param listSingletons  the list of singleton constants
     * @param continuation    the continuation to proceed with after initialization completes
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    public static int initConstants(Frame frame, List<SingletonConstant> listSingletons,
                                    Frame.Continuation continuation)
        {
        boolean fMainContext = false;

        for (SingletonConstant constSingleton : listSingletons)
            {
            ObjectHandle hValue = constSingleton.getHandle();
            if (hValue != null)
                {
                continue;
                }

            ServiceContext ctxCurr = frame.f_context;
            if (!fMainContext)
                {
                ServiceContext ctxMain = ctxCurr.getMainContext();

                if (ctxCurr == ctxMain)
                    {
                    fMainContext = true;
                    }
                else
                    {
                    assert continuation != null;

                    // we have at least one non-initialized singleton;
                    // call the main service to initialize them all
                    CompletableFuture<ObjectHandle> cfResult =
                        ctxMain.sendConstantRequest(frame, listSingletons);

                    // create a pseudo frame to deal with the wait
                    Frame frameWait = Utils.createWaitFrame(frame, cfResult, Op.A_IGNORE);
                    frameWait.addContinuation(continuation);

                    return frame.call(frameWait);
                    }
                }

            // we are on the main context and can actually perform the initialization
            if (!constSingleton.markInitializing())
                {
                // this can only happen if we are called recursively
                return frame.raiseException("Circular initialization");
                }

            IdentityConstant constValue = constSingleton.getValue();
            int              iResult;

            switch (constValue.getFormat())
                {
                case Module:
                    iResult = xModule.INSTANCE.createConstHandle(frame, constValue);
                    break;

                case Package:
                    iResult = xPackage.INSTANCE.createConstHandle(frame, constValue);
                    break;

                case Property:
                    iResult = callPropertyInitializer(frame, (PropertyConstant) constValue);
                    break;

                case Class:
                    {
                    ClassConstant idClz = (ClassConstant) constValue;
                    ClassStructure clz  = (ClassStructure) idClz.getComponent();

                    assert clz.isSingleton();

                    ClassTemplate template = ctxCurr.f_templates.getTemplate(idClz);
                    if (template.getStructure().getFormat() == Format.ENUMVALUE)
                        {
                        // this can happen if the constant's handle was not initialized or
                        // assigned on a different constant pool
                        iResult = template.createConstHandle(frame, constSingleton);
                        }
                    else
                        {
                        // the class must have a no-params constructor to call
                        MethodStructure constructor = clz.findMethod(frame.poolContext().sigConstruct());
                        iResult = constructor == null
                            ? frame.raiseException(
                                "Missing default constructor at " + clz.getSimpleName())
                            : template.construct(frame, constructor,
                                template.getCanonicalClass(), null, Utils.OBJECTS_NONE, Op.A_STACK);
                        }
                    break;
                    }

                default:
                    throw new IllegalStateException("unexpected defining constant: " + constValue);
                }

            switch (iResult)
                {
                case Op.R_NEXT:
                    constSingleton.setHandle(frame.popStack());
                    break; // next constant

                case Op.R_EXCEPTION:
                    return Op.R_EXCEPTION;

                case Op.R_CALL:
                    frame.m_frameNext.addContinuation(frameCaller ->
                        {
                        constSingleton.setHandle(frameCaller.popStack());
                        return initConstants(frameCaller, listSingletons, continuation);
                        });
                    return Op.R_CALL;

                default:
                    throw new IllegalStateException();
                }
            }

        return continuation.proceed(frame);
        }

    /**
     * Call the static property initializer.
     *
     * @param frame   the caller's frame
     * @param idProp  the property id
     *
     * @return one of the {@link Op#R_NEXT}, {@link Op#R_CALL} or {@link Op#R_EXCEPTION} values
     */
    private static int callPropertyInitializer(Frame frame, PropertyConstant idProp)
        {
        PropertyStructure prop   = (PropertyStructure) idProp.getComponent();
        ObjectHandle      hValue;

        assert prop.isStatic();

        Constant constVal = prop.getInitialValue();
        if (constVal == null)
            {
            // there must be an initializer
            MethodStructure methodInit = prop.getInitializer();
            ObjectHandle[]  ahVar      =
                Utils.ensureSize(Utils.OBJECTS_NONE, methodInit.getMaxVars());

            return frame.call1(methodInit, null, ahVar, Op.A_STACK);
            }

        hValue = frame.getConstHandle(constVal);
        if (Op.isDeferred(hValue))
            {
            ObjectHandle[] ahValue = new ObjectHandle[] {hValue};
            Frame.Continuation stepNext = frameCaller ->
                {
                frame.pushStack(ahValue[0]);
                return Op.R_NEXT;
                };
            return new Utils.GetArguments(ahValue, stepNext).doNext(frame);
            }

        frame.pushStack(hValue);
        return Op.R_NEXT;
        }

    /**
     * Create a pseudo frame that will wait on the specified future.
     *
     * @param frame     the caller frame
     * @param cfResult  the CompletableFuture to wait for
     * @param iReturn   the return register for the result
     *
     * @return a new frame
     */
    public static Frame createWaitFrame(Frame frame,
                                        CompletableFuture<ObjectHandle> cfResult, int iReturn)
        {
        ObjectHandle[] ahFuture = new ObjectHandle[]{xFutureVar.makeHandle(cfResult)};

        Frame frameNext = frame.createNativeFrame(GET_AND_RETURN, ahFuture, iReturn, null);

        frameNext.f_aInfo[0] = frame.new VarInfo(xFutureVar.TYPE, Frame.VAR_DYNAMIC_REF);

        return frameNext;
        }

    /**
     * Create a pseudo frame that will wait on multiple specified futures.
     *
     * @param frame     the caller frame
     * @param cfResult  the CompletableFuture to wait for
     * @param aiReturn  the return registers for the results
     *
     * @return a new frame
     */
    public static Frame createWaitFrame(Frame frame,
                                        CompletableFuture<ObjectHandle[]> cfResult, int[] aiReturn)
        {
        int            cReturns = aiReturn.length;
        ObjectHandle[] ahFuture = new ObjectHandle[cReturns];
        Frame frameNext = frame.createNativeFrame(GET_AND_RETURN, ahFuture, Op.A_MULTI, aiReturn);

        // create a pseudo frame to deal with the multiple waits
        for (int i = 0; i < cReturns; i++)
            {
            int iResult = i;

            CompletableFuture<ObjectHandle> cfReturn =
                    cfResult.thenApply(ahResult -> ahResult[iResult]);

            ahFuture[i] = xFutureVar.makeHandle(cfReturn);
            frameNext.f_aInfo[i] = frame.new VarInfo(xFutureVar.TYPE, Frame.VAR_DYNAMIC_REF);
            }

        return frameNext;
        }

    private static final Op[] GET_AND_RETURN = new Op[]
        {
        new Op()
            {
            public int process(Frame frame, int iPC)
                {
                try
                    {
                    int cValues = frame.f_ahVar.length;

                    assert cValues > 0;

                    if (cValues == 1)
                        {
                        assert frame.f_aiReturn == null;

                        ObjectHandle hValue = frame.getArgument(0);
                        if (hValue == null)
                            {
                            return R_REPEAT;
                            }
                        // getArgument() call has already de-referenced the dynamic register
                        return frame.returnValue(hValue, false);
                        }

                    assert frame.f_iReturn == A_MULTI;

                    ObjectHandle[] ahValue = new ObjectHandle[cValues];
                    for (int i = 0; i < cValues; i++)
                        {
                        ObjectHandle hValue = frame.getArgument(i);
                        if (hValue == null)
                            {
                            return R_REPEAT;
                            }

                        // services may replace "null" elements of a negative conditional return
                        // with the DEFAULT value (see ServiceContext.sendResponse)
                        if (hValue == ObjectHandle.DEFAULT)
                            {
                            assert i > 0 && ahValue[0].equals(xBoolean.FALSE);
                            hValue = null;
                            }
                        ahValue[i] = hValue;
                        }

                    return frame.returnValues(ahValue, null);
                    }
                catch (ObjectHandle.ExceptionHandle.WrapperException e)
                    {
                    return frame.raiseException(e);
                    }
                }

            public String toString()
                {
                return "GetAndReturn";
                }
            }
        };

    /**
     * An abstract base for in-place operation support.
     */
    public static abstract class AbstractInPlace
            implements Frame.Continuation
        {
        protected ObjectHandle hValueOld;
        protected ObjectHandle hValueNew;
        protected int ixStep = -1;

        @Override
        public int proceed(Frame frameCaller)
            {
            updateResult(frameCaller);

            return doNext(frameCaller);
            }

        protected void updateResult(Frame frameCaller)
            {
            switch (ixStep)
                {
                case 0: // get
                    hValueOld = frameCaller.popStack();
                    break;

                case 1: // action
                    hValueNew = frameCaller.popStack();
                    break;

                case 2: // set
                    break;

                default:
                    throw new IllegalStateException();
                }
            }

        public abstract int doNext(Frame frameCaller);
        }

    /**
     * In place property unary operation support.
     */
    public static class InPlacePropertyUnary
            extends AbstractInPlace
        {
        private final UnaryAction action;
        private final ClassTemplate template;
        private final ObjectHandle hTarget;
        private final PropertyConstant idProp;
        private final boolean fPost;
        private final int iReturn;

        protected InPlacePropertyUnary(UnaryAction action, ClassTemplate template,
                                       ObjectHandle hTarget, PropertyConstant idProp, boolean fPost,
                                       int iReturn)
            {
            this.action = action;
            this.template = template;
            this.hTarget = hTarget;
            this.idProp = idProp;
            this.fPost = fPost;
            this.iReturn = iReturn;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int iResult;
                switch (++ixStep)
                    {
                    case 0:
                        iResult = template.
                            getPropertyValue(frameCaller, hTarget, idProp, Op.A_STACK);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld);
                        break;

                    case 2:
                        iResult = template.
                            setPropertyValue(frameCaller, hTarget, idProp, hValueNew);
                        break;

                    case 3:
                        return frameCaller.assignValue(iReturn, fPost ? hValueOld : hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    /**
     * In place property binary operation support.
     */
    public static class InPlacePropertyBinary
            extends AbstractInPlace
        {
        private final BinaryAction action;
        private final ClassTemplate template;
        private final ObjectHandle hTarget;
        private final PropertyConstant idProp;
        private final ObjectHandle hArg;

        protected InPlacePropertyBinary(BinaryAction action, ClassTemplate template,
                                        ObjectHandle hTarget, PropertyConstant idProp, ObjectHandle hArg)
            {
            this.action = action;
            this.template = template;
            this.hTarget = hTarget;
            this.idProp = idProp;
            this.hArg = hArg;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int iResult;
                switch (++ixStep)
                    {
                    case 0:
                        iResult = template.getPropertyValue(frameCaller, hTarget, idProp, Op.A_STACK);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld, hArg);
                        break;

                    case 2:
                        return template.setPropertyValue(frameCaller, hTarget, idProp, hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    /**
     * In place Var unary operation support.
     */
    public static class InPlaceVarUnary
            extends AbstractInPlace
        {
        private final UnaryAction action;
        private final RefHandle hTarget;
        private final boolean fPost;
        private final int iReturn;

        /**
         * @param action   the action
         * @param hTarget  the target Var
         * @param fPost    if true, the the operation is performed after the current value is returned
 *                 (e.g. i--); otherwise - before that (e.g. --i)
         * @param iReturn  the register to place the result of the operation into
         */
        public InPlaceVarUnary(UnaryAction action, RefHandle hTarget, boolean fPost, int iReturn)
            {
            this.action = action;
            this.hTarget = hTarget;
            this.fPost = fPost;
            this.iReturn = iReturn;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int nStep = ++ixStep;

                int iResult;
                switch (nStep)
                    {
                    case 0:
                        iResult = hTarget.getVarSupport().getReferent(frameCaller, hTarget, Op.A_STACK);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld);
                        break;

                    case 2:
                        iResult = hTarget.getVarSupport().setReferent(frameCaller, hTarget, hValueNew);
                        break;

                    case 3:
                        return frameCaller.assignValue(iReturn, fPost ? hValueOld : hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    /**
     * In place Var binary operation support.
     */
    public static class InPlaceVarBinary
            extends AbstractInPlace
        {
        private final BinaryAction action;
        private final RefHandle hTarget;
        private final ObjectHandle hArg;

        public InPlaceVarBinary(BinaryAction action, RefHandle hTarget, ObjectHandle hArg)
            {
            this.action = action;
            this.hTarget = hTarget;
            this.hArg = hArg;
            }

        public int doNext(Frame frameCaller)
            {
            while (true)
                {
                int iResult;
                switch (++ixStep)
                    {
                    case 0:
                        iResult = hTarget.getVarSupport().getReferent(frameCaller, hTarget, Op.A_STACK);
                        break;

                    case 1:
                        iResult = action.invoke(frameCaller, hValueOld, hArg);
                        break;

                    case 2:
                        return hTarget.getVarSupport().setReferent(frameCaller, hTarget, hValueNew);

                    default:
                        throw new IllegalStateException();
                    }

                switch (iResult)
                    {
                    case Op.R_NEXT:
                        updateResult(frameCaller);
                        break;

                    case Op.R_CALL:
                        frameCaller.m_frameNext.addContinuation(this);
                        return Op.R_CALL;

                    case Op.R_EXCEPTION:
                        return Op.R_EXCEPTION;

                    default:
                        throw new IllegalArgumentException();
                    }
                }
            }
        }

    // the lambda for unary actions
    public interface UnaryAction
        {
        // invoke and place the result into A_LOCAL
        int invoke(Frame frame, ObjectHandle hTarget);

        // ----- action constants ------------------------------------------------------------------

        UnaryAction INC = (frameCaller, hValue) ->
            hValue.getOpSupport().invokeNext(frameCaller, hValue, Op.A_STACK);

        UnaryAction DEC = (frameCaller, hValue) ->
            hValue.getOpSupport().invokePrev(frameCaller, hValue, Op.A_STACK);
        }

    // the lambda for binary actions
    public interface BinaryAction
        {
        // invoke and place the result into A_LOCAL
        int invoke(Frame frame, ObjectHandle hTarget, ObjectHandle hArg);

        // ----- action constants ------------------------------------------------------------------

        BinaryAction ADD = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeAdd(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction SUB = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeSub(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction MUL = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeMul(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction DIV = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeDiv(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction MOD = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeMod(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction SHL = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeShl(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction SHR = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeShr(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction USHR = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeShrAll(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction AND = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeAnd(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction OR = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeOr(frameCaller, hValue, hArg, Op.A_STACK);

        BinaryAction XOR = (frameCaller, hValue, hArg) ->
            hValue.getOpSupport().invokeXor(frameCaller, hValue, hArg, Op.A_STACK);
        }
    }
