package org.xvm.asm.op;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.xvm.asm.Constant;
import org.xvm.asm.Op;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;

import org.xvm.runtime.template.collections.xTuple.TupleHandle;

import static org.xvm.util.Handy.readPackedInt;
import static org.xvm.util.Handy.writePackedLong;


/**
 * RETURN_T rvalue-tuple ; return (a tuple of return values)
 *
 * (generated by the compiler when the current function has a multi-return, but the
 *  specified register is a tuple)
 */
public class Return_T
        extends Op
    {
    /**
     * Construct a RETURN_T op.
     *
     * @param nValue  the tuple value to return
     */
    public Return_T(int nValue)
        {
        f_nArgValue = nValue;
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public Return_T(DataInput in, Constant[] aconst)
            throws IOException
        {
        f_nArgValue = readPackedInt(in);
        }

    @Override
    public void write(DataOutput out, ConstantRegistry registry)
    throws IOException
        {
        out.writeByte(OP_RETURN_T);
        writePackedLong(out, f_nArgValue);
        }

    @Override
    public int getOpCode()
        {
        return OP_RETURN_T;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        int iRet = frame.f_iReturn;
        if (iRet >= 0 || iRet == Frame.RET_LOCAL)
            {
            throw new IllegalStateException(); // assertion
            }

        switch (iRet)
            {
            case Frame.RET_UNUSED:
                break;

            case Frame.RET_MULTI:
                TupleHandle hTuple;
                try
                    {
                    hTuple = (TupleHandle) frame.getArgument(f_nArgValue);
                    if (hTuple == null)
                        {
                        return R_REPEAT;
                        }
                    }
                catch (ExceptionHandle.WrapperException e)
                    {
                    return frame.raiseException(e);
                    }

                int[] aiRet = frame.f_aiReturn;
                ObjectHandle[] ahValue = hTuple.m_ahValue;

                // it's possible that the caller doesn't care about some of the return values
                for (int i = 0, c = aiRet.length; i < c; i++)
                    {
                    int iResult = frame.f_framePrev.assignValue(aiRet[i], ahValue[i]);
                    switch (iResult)
                        {
                        case Op.R_EXCEPTION:
                            return Op.R_RETURN_EXCEPTION;

                        case Op.R_BLOCK:
                            // tuple's value cannot be a synthetic future
                            throw new IllegalStateException();
                        }
                    }
                break;

            default:
                // pass the tuple "as is"
                return frame.returnValue(-iRet - 1, f_nArgValue);
            }
        return R_RETURN;
        }

    private final int f_nArgValue;
    }
