package org.xvm.runtime.template._native.reflect;


import org.xvm.asm.ClassStructure;
import org.xvm.asm.Constant;
import org.xvm.asm.ConstantPool;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.Op;

import org.xvm.asm.constants.ClassConstant;
import org.xvm.asm.constants.IdentityConstant;
import org.xvm.asm.constants.TypeConstant;

import org.xvm.runtime.ClassComposition;
import org.xvm.runtime.ClassTemplate;
import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.TemplateRegistry;
import org.xvm.runtime.Utils;

import org.xvm.runtime.template.xBoolean;
import org.xvm.runtime.template.xConst;


/**
 * Native Class implementation.
 */
public class xRTClass
        extends xConst
    {
    public static xRTClass INSTANCE;

    public xRTClass(TemplateRegistry templates, ClassStructure structure, boolean fInstance)
        {
        super(templates, structure, false);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    public void initNative()
        {
        markNativeProperty("abstract");
        markNativeProperty("composition");
        markNativeProperty("formalTypes");
        markNativeProperty("name");
        markNativeProperty("virtualChild");

        markNativeMethod("allocate"    , null, null);
        markNativeMethod("derivesFrom" , null, null);
        markNativeMethod("extends"     , null, null);
        markNativeMethod("implements"  , null, null);
        markNativeMethod("incorporates", null, null);
        markNativeMethod("isSingleton" , null, null);

        getCanonicalType().invalidateTypeInfo();
        }

    @Override
    public int createConstHandle(Frame frame, Constant constant)
        {
        if (constant instanceof ClassConstant)
            {
            ClassConstant    idClz    = (ClassConstant) constant;
            TypeConstant     typeClz  = idClz.getValueType(null);
            ClassComposition clz      = ensureClass(typeClz);
            ClassTemplate    template = clz.getTemplate();

            MethodStructure constructor = getStructure().findMethod("construct", 0);
            ObjectHandle[]  ahVar       = new ObjectHandle[constructor.getMaxVars()];

            return template.construct(frame, constructor, clz, null, ahVar, Op.A_STACK);
            }

        return super.createConstHandle(frame, constant);
        }

    @Override
    public int invokeNativeGet(Frame frame, String sPropName, ObjectHandle hTarget, int iReturn)
        {
        switch (sPropName)
            {
            case "abstract":
                return getPropertyAbstract(frame, hTarget, iReturn);

            case "composition":
                return getPropertyComposition(frame, hTarget, iReturn);

            case "formalTypes":
                return getPropertyFormatTypes(frame, hTarget, iReturn);

            case "name":
                return getPropertyName(frame, hTarget, iReturn);

            case "virtualChild":
                return getPropertyVirtualChild(frame, hTarget, iReturn);
            }

        return super.invokeNativeGet(frame, sPropName, hTarget, iReturn);
        }

    @Override
    public int invokeNativeNN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                              ObjectHandle[] ahArg, int[] aiReturn)
        {
        switch (method.getName())
            {
            case "allocate":
                return invokeAllocate(frame, hTarget, aiReturn);

            case "isSingleton":
                return invokeIsSingleton(frame, hTarget, aiReturn);

            // TODO CP
            }

        return super.invokeNativeNN(frame, method, hTarget, ahArg, aiReturn);
        }


    // ----- property implementations --------------------------------------------------------------

    /**
     * Implements property: abstract.get()
     */
    public int getPropertyAbstract(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        TypeConstant typeTarget = getClassType(hTarget);
        ObjectHandle hResult    = null; // TODO CP
        return frame.assignValue(iReturn, hResult);
        }

    /**
     * Implements property: composition.get()
     */
    public int getPropertyComposition(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        TypeConstant typeTarget = getClassType(hTarget);
        ObjectHandle hResult    = null; // TODO CP
        return frame.assignValue(iReturn, hResult);
        }

    /**
     * Implements property: formalTypes.get()
     */
    public int getPropertyFormatTypes(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        TypeConstant typeTarget = getClassType(hTarget);
        ObjectHandle hResult    = null; // TODO CP
        return frame.assignValue(iReturn, hResult);
        }

    /**
     * Implements property: name.get()
     */
    public int getPropertyName(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        TypeConstant typeTarget = getClassType(hTarget);
        ObjectHandle hResult    = null; // TODO CP
        return frame.assignValue(iReturn, hResult);
        }

    /**
     * Implements property: virtualChild.get()
     */
    public int getPropertyVirtualChild(Frame frame, ObjectHandle hTarget, int iReturn)
        {
        TypeConstant typeTarget = getClassType(hTarget);
        ObjectHandle hResult    = xBoolean.makeHandle(typeTarget.isVirtualChild());
        return frame.assignValue(iReturn, hResult);
        }


    // ----- method implementations ----------------------------------------------------------------

    /**
     * Implementation for: {@code conditional StructType allocate()}.
     */
    public int invokeAllocate(Frame frame, ObjectHandle hTarget, int[] aiReturn)
        {
        TypeConstant typeClz    = hTarget.getType();
        TypeConstant typePublic = typeClz.getParamType(0);

        if (typePublic.isImmutabilitySpecified())
            {
            typePublic = typePublic.getUnderlyingType();
            }
        if (typePublic.isAccessSpecified())
            {
            typePublic = typePublic.getUnderlyingType();
            }

        ClassTemplate template = f_templates.getTemplate(typePublic);

        switch (template.getStructure().getFormat())
            {
            case CLASS:
            case CONST:
            case MIXIN:
            case ENUMVALUE:
                break;

            default:
                return frame.assignValue(aiReturn[0], xBoolean.FALSE);
            }

        ClassComposition clz      = f_templates.resolveClass(typePublic);
        ObjectHandle     hStruct  = template.createStruct(frame, clz);
        MethodStructure  methodAI = clz.ensureAutoInitializer();
        if (methodAI != null)
            {
            switch (frame.call1(methodAI, hStruct, Utils.OBJECTS_NONE, Op.A_IGNORE))
                {
                case Op.R_NEXT:
                    break;

                case Op.R_CALL:
                    frame.m_frameNext.addContinuation(frameCaller ->
                        frameCaller.assignValues(aiReturn, xBoolean.TRUE, hStruct));
                    return Op.R_CALL;

                case Op.R_EXCEPTION:
                    return Op.R_EXCEPTION;

                default:
                    throw new IllegalStateException();
                }
            }
        return frame.assignValues(aiReturn, xBoolean.TRUE, hStruct);
        }

    /**
     * Implementation for: {@code conditional PublicType isSingleton()}.
     */
    public int invokeIsSingleton(Frame frame, ObjectHandle hTarget, int[] aiReturn)
        {
        TypeConstant typeClz = getClassType(hTarget);
        if (typeClz.ensureTypeInfo().isSingleton())
            {
            IdentityConstant idClz         = typeClz.getSingleUnderlyingClass(false);
            ConstantPool     pool          = frame.poolContext();
            Constant         constInstance = pool.ensureSingletonConstConstant(idClz);
            ObjectHandle     hInstance     = frame.getConstHandle(constInstance);
            if (Op.isDeferred(hInstance))
                {
                ObjectHandle[] ahValue = new ObjectHandle[] {hInstance};
                Frame.Continuation stepNext = frameCaller ->
                    frameCaller.assignValues(aiReturn, xBoolean.TRUE, ahValue[0]);
                return new Utils.GetArguments(ahValue, stepNext).doNext(frame);
                }
            return frame.assignValues(aiReturn, xBoolean.TRUE, hInstance);
            }
        return frame.assignValue(aiReturn[0], xBoolean.FALSE);
        }


    // ----- helpers -------------------------------------------------------------------------------

    /**
     * Return the type of the Class, reverse-engineered from the PublicType.
     *
     * @param hTarget  the handle for the instance of Class
     *
     * @return the TypeConstant for the Class
     */
    protected TypeConstant getClassType(ObjectHandle hTarget)
        {
        return hTarget.getComposition().getType().getParamType(0).getUnderlyingType();
        }
    }
