package org.xvm.proto.template;

import org.xvm.asm.ClassStructure;
import org.xvm.asm.Constant;
import org.xvm.asm.MethodStructure;
import org.xvm.asm.constants.CharStringConstant;

import org.xvm.proto.Frame;
import org.xvm.proto.ObjectHandle;
import org.xvm.proto.ObjectHandle.JavaLong;
import org.xvm.proto.ObjectHeap;
import org.xvm.proto.Op;
import org.xvm.proto.TypeComposition;
import org.xvm.proto.ClassTemplate;
import org.xvm.proto.TypeSet;

/**
 * TODO:
 *
 * @author gg 2017.02.27
 */
public class xString
        extends ClassTemplate
    {
    public static xString INSTANCE;

    public xString(TypeSet types, ClassStructure structure, boolean fInstance)
        {
        super(types, structure);

        if (fInstance)
            {
            INSTANCE = this;
            }
        }

    @Override
    public void initDeclared()
        {
        markNativeGetter("size");
        markNativeMethod("indexOf", new String[]{"String", "Range<Int64>?"},
                new String[]{"Boolean", "Int64"});
        markNativeMethod("to", VOID, STRING);
        }

    @Override
    public ObjectHandle createConstHandle(Constant constant, ObjectHeap heap)
        {
        return constant instanceof CharStringConstant ? new StringHandle(f_clazzCanonical,
                ((CharStringConstant) constant).getValue()) : null;
        }

    @Override
    public int invokeNativeN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle[] ahArg, int iReturn)
        {
        StringHandle hThis = (StringHandle) hTarget;

        switch (ahArg.length)
            {
            case 0:
                switch (method.getName())
                    {
                    case "get": // size.get()
                        assert method.getParent().getParent().getName().equals("size");

                        ObjectHandle hResult = xInt64.makeHandle(hThis.m_sValue.length());
                        return frame.assignValue(iReturn, hResult);
                    }
                break;

            case 2:
                switch (method.getName())
                    {
                    case "indexOf": // indexOf(String s, Int n)
                        String s = ((StringHandle) ahArg[0]).getValue();
                        int n = (int) ((JavaLong) ahArg[1]).getValue();

                        ObjectHandle hResult = xInt64.makeHandle(hThis.m_sValue.indexOf(s, n));
                        return frame.assignValue(iReturn, hResult);
                    }
                break;

            }

        return super.invokeNativeN(frame, method, hTarget, ahArg, iReturn);
        }

    @Override
    public int invokeNative1(Frame frame, MethodStructure method, ObjectHandle hTarget,
                             ObjectHandle hArg, int iReturn)
        {
        StringHandle hThis = (StringHandle) hTarget;

        switch (method.getName())
            {
            case "indexOf": // indexOf(String)
                if (hArg instanceof StringHandle)
                    {
                    int nOf = hThis.m_sValue.indexOf(((StringHandle) hArg).m_sValue);

                    return frame.assignValue(iReturn, xInt64.makeHandle(nOf));
                    }
            }

        return super.invokeNative1(frame, method, hTarget, hArg, iReturn);
        }

    @Override
    public int invokeNativeNN(Frame frame, MethodStructure method, ObjectHandle hTarget,
                              ObjectHandle[] ahArg, int[] aiReturn)
        {
        StringHandle hThis = (StringHandle) hTarget;

        switch (ahArg.length)
            {
            case 2:
                switch (method.getName())
                    {
                    case "indexOf": // (Boolean, Int) indexOf(String s, Range<Int>? range)
                        String s = ((StringHandle) ahArg[0]).getValue();
                        ObjectHandle hRange = ahArg[1];
                        if (hRange == xNullable.NULL)
                            {
                            int of = hThis.m_sValue.indexOf(s);
                            if (of >= 0)
                                {
                                int nR = frame.assignValue(aiReturn[0], xBoolean.TRUE);
                                if (nR == Op.R_EXCEPTION)
                                    {
                                    return Op.R_EXCEPTION;
                                    }
                                return frame.assignValue(aiReturn[1], xInt64.makeHandle(of));
                                }
                            }
                        else
                            {
                            // TODO: parse the range
                            }
                    }
                break;
            }

        return super.invokeNativeNN(frame, method, hTarget, ahArg, aiReturn);
        }

    @Override
    public int invokeAdd(Frame frame, ObjectHandle hTarget, ObjectHandle hArg, int iReturn)
        {
        StringHandle hThis = (StringHandle) hTarget;
        StringHandle hThat = (StringHandle) hArg;

        return frame.assignValue(iReturn, makeHandle(hThis.m_sValue + hThat.m_sValue));
        }

    // ----- comparison support -----

    @Override
    public int callEquals(Frame frame, TypeComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        StringHandle h1 = (StringHandle) hValue1;
        StringHandle h2 = (StringHandle) hValue2;

        return frame.assignValue(iReturn,
                xBoolean.makeHandle(h1.getValue().equals(h2.getValue())));
        }

    @Override
    public int callCompare(Frame frame, TypeComposition clazz,
                          ObjectHandle hValue1, ObjectHandle hValue2, int iReturn)
        {
        StringHandle h1 = (StringHandle) hValue1;
        StringHandle h2 = (StringHandle) hValue2;

        return frame.assignValue(iReturn,
                xOrdered.makeHandle(h1.getValue().compareTo(h2.getValue())));
        }

    // ----- Object methods -----

    @Override
    public ObjectHandle.ExceptionHandle buildStringValue(ObjectHandle hTarget, StringBuilder sb)
        {
        sb.append(((StringHandle) hTarget).getValue());
        return null;
        }

    public static class StringHandle
            extends ObjectHandle
        {
        private String m_sValue = UNASSIGNED;

        protected StringHandle(TypeComposition clazz, String sValue)
            {
            super(clazz);

            m_sValue = sValue;
            }

        public String getValue()
            {
            return m_sValue;
            }

        @Override
        public String toString()
            {
            return super.toString() + m_sValue;
            }

        private final static String UNASSIGNED = "\0UNASSIGNED\0";
        }

    public static StringHandle makeHandle(String sValue)
        {
        return new StringHandle(INSTANCE.f_clazzCanonical, sValue);
        }
    }
