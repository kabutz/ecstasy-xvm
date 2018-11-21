package org.xvm.asm.op;


import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import org.xvm.asm.Argument;
import org.xvm.asm.Constant;
import org.xvm.asm.Op;

import org.xvm.runtime.Frame;
import org.xvm.runtime.ObjectHandle;
import org.xvm.runtime.ObjectHandle.ExceptionHandle;
import org.xvm.runtime.Utils;

import org.xvm.runtime.template.xBoolean.BooleanHandle;
import org.xvm.runtime.template.xException;

import static org.xvm.util.Handy.readPackedInt;
import static org.xvm.util.Handy.writePackedLong;


/**
 * ASSERT rvalue
 */
public class Assert
        extends Op
    {
    /**
     * Construct an ASSERT op.
     *
     * @param nTest  the r-value of the assertion expression
     *
     * @deprecated
     */
    public Assert(int nTest)
        {
        m_nTest = nTest;
        }

    /**
     * Construct an ASSERT op based on the specified arguments.
     *
     * @param argTest  the test Argument
     */
    public Assert(Argument argTest)
        {
        m_argTest = argTest;
        }

    /**
     * Deserialization constructor.
     *
     * @param in      the DataInput to read from
     * @param aconst  an array of constants used within the method
     */
    public Assert(DataInput in, Constant[] aconst)
            throws IOException
        {
        m_nTest = readPackedInt(in);
        }

    @Override
    public void write(DataOutput out, ConstantRegistry registry)
            throws IOException
        {
        if (m_argTest != null)
            {
            m_nTest = encodeArgument(m_argTest, registry);
            }

        out.writeByte(OP_ASSERT);
        writePackedLong(out, m_nTest);
        }

    @Override
    public int getOpCode()
        {
        return OP_ASSERT;
        }

    @Override
    public int process(Frame frame, int iPC)
        {
        try
            {
            ObjectHandle hValue = frame.getArgument(m_nTest);
            if (hValue == null)
                {
                return R_REPEAT;
                }

            if (isDeferred(hValue))
                {
                ObjectHandle[] ahValue = new ObjectHandle[] {hValue};
                Frame.Continuation stepNext = frameCaller ->
                    complete(frameCaller, iPC, (BooleanHandle) ahValue[0]);

                return new Utils.GetArguments(ahValue, stepNext).doNext(frame);
                }

            return complete(frame, iPC, (BooleanHandle) hValue);
            }
        catch (ExceptionHandle.WrapperException e)
            {
            return frame.raiseException(e);
            }
        }

    protected int complete(Frame frame, int iPC, BooleanHandle hTest)
        {
        if (hTest.get())
            {
            return iPC + 1;
            }

        return frame.raiseException(xException.makeHandle("Assertion failed"));
        }

    @Override
    public void registerConstants(ConstantRegistry registry)
        {
        super.registerConstants(registry);

        m_argTest = registerArgument(m_argTest, registry);
        }

    @Override
    public String toString()
        {
        return super.toString() + ' ' + Argument.toIdString(m_argTest, m_nTest);
        }

    private int m_nTest;

    private Argument m_argTest;
    }
