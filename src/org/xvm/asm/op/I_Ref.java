package org.xvm.asm.op;


import java.io.DataInput;
import java.io.IOException;

import org.xvm.asm.Constant;
import org.xvm.asm.OpIndex;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.JavaLong;

import org.xvm.runtime.template.IndexSupport;


/**
 * I_REF rvalue-target, rvalue-ix, lvalue ; Ref<T> = &T[ix]
 */
public class I_Ref
        extends OpIndex
    {
    /**
     * Construct an I_REF op.
     *
     * @param nTarget  the target array
     * @param nIndex   the index of the value in the array
     * @param nRet     the location to store the reference to the value in the array
     *
     * @deprecated
     */
    public I_Ref(int nTarget, int nIndex, int nRet)
        {
        super(null, null, null);

        m_nTarget = nTarget;
        m_nIndex = nIndex;
        m_nRetValue = nRet;
        }

    /**
     * Construct an I_REF op for the passed arguments.
     *
     * @param argTarget  the target Argument
     * @param argIndex   the index Argument
     * @param argReturn  the Argument to store the result into
     */
    public I_Ref(Argument argTarget, Argument argIndex, Argument argReturn)
        {
        super(argTarget, argIndex, argReturn);
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public I_Ref(DataInput in, Constant[] aconst)
            throws IOException
        {
        super(in, aconst);
        }

    @Override
    public int getOpCode()
        {
        return OP_I_REF;
        }

    @Override
    protected void introduceAssignVar(Frame frame)
        {
        if (frame.isNextRegister(m_nRetValue))
            {
            frame.introduceElementRef(m_nTarget);
            }
        }

    @Override
    protected int complete(Frame frame, ObjectHandle hTarget, JavaLong hIndex)
        {
        IndexSupport template = (IndexSupport) hTarget.getTemplate();

        return template.makeRef(frame, hTarget, hIndex.getValue(), true, m_nRetValue);
        }
    }
