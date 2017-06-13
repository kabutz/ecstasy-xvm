package org.xvm.proto;

import org.xvm.proto.TypeCompositionTemplate.ConstructTemplate;
import org.xvm.proto.TypeCompositionTemplate.InvocationTemplate;
import org.xvm.proto.TypeCompositionTemplate.PropertyTemplate;

import org.xvm.proto.ObjectHandle.ExceptionHandle;
import org.xvm.proto.op.Return_0;

import org.xvm.proto.template.xFunction.FunctionHandle;
import org.xvm.proto.template.xFutureRef;
import org.xvm.proto.template.xFutureRef.FutureHandle;
import org.xvm.proto.template.xService;
import org.xvm.proto.template.xService.PropertyOperation;
import org.xvm.proto.template.xService.PropertyOperation10;
import org.xvm.proto.template.xService.PropertyOperation01;
import org.xvm.proto.template.xService.PropertyOperation11;
import org.xvm.proto.template.xService.ServiceHandle;

import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;

import java.util.function.Supplier;

/**
 * The service context.
 *
 * @author gg 2017.02.15
 */
public class ServiceContext
    {
    public final Container f_container;
    public final TypeSet f_types;
    public final ObjectHeap f_heapGlobal;
    public final ConstantPoolAdapter f_constantPool;

    private final Queue<Message> f_queueMsg;
    private final Queue<Response> f_queueResponse;

    private final int f_nId; // the service id
    private final String f_sName; // the service name

    protected ServiceHandle m_hService;

    /**
     * The current Timeout that will be used by the service when it invokes other services.
     */
    public long m_cTimeoutMillis;

    // Metrics: the total time (in nanos) this service has been running
    protected long m_cRuntimeNanos;

    private  Frame m_frameCurrent;
    private List<Frame> f_listSuspended = new LinkedList<>(); // suspended fibers

    enum Reentrancy {Prioritized, Open, Exclusive, Forbidden}
    volatile Reentrancy m_reentrancy = Reentrancy.Prioritized;

    enum ServiceStatus
        {
        Idle,
        Busy,
        ShuttingDown,
        Terminated,
        }
    volatile ServiceStatus m_status = ServiceStatus.Idle;

    enum FiberStatus
        {
        Initial, // the frame has not been scheduled for execution yet
        Running, // normal execution
        Paused,  // the execution was paused by the scheduler
        Yielded, // the execution was explicitly yielded by the user code
        Waiting, // execution is blocked until the "waiting" futures are completed
        }

    final static ThreadLocal<ServiceContext> s_tloContext = new ThreadLocal<>();

    ServiceContext(Container container, String sName, int nId)
        {
        f_container = container;
        f_sName = sName;
        f_nId = nId;

        f_heapGlobal = container.f_heapGlobal;
        f_types = container.f_types;
        f_constantPool = container.f_constantPoolAdapter;
        f_queueMsg = new ConcurrentLinkedQueue<>();
        f_queueResponse = new ConcurrentLinkedQueue<>();
        }

    public Frame getCurrentFrame()
        {
        return m_frameCurrent;
        }

    public static ServiceContext getCurrentContext()
        {
        return s_tloContext.get();
        }

    public void setService(ServiceHandle hService)
        {
        assert m_hService == null;
        m_hService = hService;
        }

    public void addRequest(Message msg)
        {
        f_queueMsg.add(msg);
        }

    public void respond(Response response)
        {
        f_queueResponse.add(response);
        }

    // get a next frame ready for execution
    public Frame nextFiber()
        {
        // responses have the highest priority and no user code runs there;
        // process all we've got so far
        for (Response response = f_queueResponse.poll(); response != null;
                      response = f_queueResponse.poll())
            {
            response.run();
            }

        // pickup all the messages, but keep them in the "initial" state
        Message message = f_queueMsg.poll();
        while (message != null)
            {
            s_tloContext.set(this);
            Frame frame = message.createFrame(this);

            assert frame.f_fiber.getStatus() == FiberStatus.Initial;

            suspendFiber(frame);
            message = f_queueMsg.poll();
            }

        if (m_frameCurrent != null)
            {
            // resume the paused frame (could be forbidden reentrancy)
            return m_frameCurrent;
            }

        if (f_listSuspended.isEmpty())
            {
            // nothing to do
            return null;
            }

        switch (m_reentrancy)
            {
            default:
            case Forbidden:
                throw new IllegalStateException(); // assert

            case Exclusive:
                {
                // don't allow a new fiber unless it belongs to already existing thread of execution
                Frame frameNext = null;
                for (Iterator<Frame> iter = f_listSuspended.iterator(); iter.hasNext();)
                    {
                    Frame frame = iter.next();
                    Fiber fiber = frame.f_fiber;

                    switch (fiber.getStatus())
                        {
                        case Initial:
                            if (frameNext == null
                                // && fiber.originatesFromEntered()
                                    )
                                 {
                                 frameNext = frame;
                                 }
                            break;

                        case Yielded:
                            if (frameNext == null)
                                {
                                frameNext = frame;
                                }
                            break;

                        case Waiting:
                            if (fiber.m_fResponded)
                                {
                                iter.remove();
                                return frame;
                                }
                            break;

                        case Running:
                        case Paused:
                            throw new IllegalStateException(); // assert
                        }
                    }

                if (frameNext != null)
                    {
                    f_listSuspended.remove(frameNext);
                    return frameNext;
                    }
                return null;
                }

            case Prioritized:
                {
                // don't start a new fiber unless there is nothing ready and the give priority
                // to already existing thread of execution
                Frame frameNext = null;
                for (Iterator<Frame> iter = f_listSuspended.iterator(); iter.hasNext();)
                    {
                    Frame frame = iter.next();
                    Fiber fiber = frame.f_fiber;

                    switch (fiber.getStatus())
                        {
                        case Initial:
                        case Yielded:
                            break;

                        case Waiting:
                            if (fiber.m_fResponded)
                                {
                                iter.remove();
                                return frame;
                                }
                            break;

                        case Running:
                        case Paused:
                            throw new IllegalStateException(); // assert
                        }
                    }

                if (frameNext != null)
                    {
                    f_listSuspended.remove(frameNext);
                    return frameNext;
                    }
                // fall through;
                }

            case Open:
                // resume a pending (for any reason) or start a new fiber in a round-robin fashion
                for (Iterator<Frame> iter = f_listSuspended.iterator(); iter.hasNext();)
                    {
                    Frame frame = iter.next();
                    Fiber fiber = frame.f_fiber;

                    switch (fiber.getStatus())
                        {
                        case Initial:
                        case Yielded:
                            iter.remove();
                            return frame;

                        case Waiting:
                            if (fiber.m_fResponded)
                                {
                                iter.remove();
                                return frame;
                                }
                            break;

                        case Running:
                        case Paused:
                            throw new IllegalStateException(); // assert
                        }
                    }
                return null;
            }
        }

    public void suspendFiber(Frame frame)
        {
        switch (frame.f_fiber.getStatus())
            {
            case Initial:
                f_listSuspended.add(frame);
                break;

            case Running:
                throw new IllegalStateException(); // assert

            case Waiting:
            case Yielded:
                if (m_reentrancy == Reentrancy.Forbidden)
                    {
                    m_frameCurrent = frame;
                    }
                else
                    {
                    m_frameCurrent = null;
                    f_listSuspended.add(frame);
                    }
                break;

            case Paused:
                // we must resume this frame
                m_frameCurrent = frame;
                break;
            }
        }

    // return null iff there the context popped up all frames
    public Frame execute(Frame frame)
        {
        Fiber fiber = frame.f_fiber;
        int iPC = frame.m_iPC;
        int iPCLast = iPC;

        if (fiber.getStatus() == FiberStatus.Waiting)
            {
            switch (frame.checkWaitingRegisters())
                {
                case Op.R_BLOCK:
                    // there are still some "waiting" registers
                    return frame;

                case Op.R_EXCEPTION:
                    iPC = Op.R_EXCEPTION;
                    break;

                case Op.R_NEXT:
                    // proceed as is
                    break;
                }
            }

        fiber.setStatus(FiberStatus.Running);
        fiber.m_fResponded = false;
        m_frameCurrent = frame;
        s_tloContext.set(this);

        Op[] abOp = frame.f_aOp;

        int nOps = 0;
        while (true)
            {
            while (iPC >= 0) // main loop
                {
                frame.m_iPC = iPC;

                if (++nOps > 10)
                    {
                    fiber.setStatus(FiberStatus.Paused);
                    return frame;
                    }

                iPC = abOp[iPC].process(frame, iPCLast = iPC);
                }

            switch (iPC)
                {
                case Op.R_NEXT:
                    iPC = iPCLast + 1;
                    break;

                case Op.R_CALL:
                    m_frameCurrent = frame.m_frameNext;
                    frame.m_iPC = iPCLast + 1;
                    frame.m_frameNext = null;
                    frame = m_frameCurrent;
                    abOp = frame.f_aOp;
                    iPC = 0;
                    break;

                case Op.R_BLOCK_RETURN:
                    fiber.setStatus(FiberStatus.Waiting);
                    // fall through

                case Op.R_RETURN:
                    Supplier<Frame> continuation = frame.m_continuation;
                    if (continuation != null)
                        {
                        Frame frameNext = continuation.get();
                        if (frameNext == null)
                            {
                            // continuation is allowed to "throw"
                            if (frame.m_hException != null)
                                {
                                // throw the exception
                                iPC = Op.R_EXCEPTION;
                                break;
                                }
                            }
                        else
                            {
                            frame = m_frameCurrent = frameNext;
                            abOp = frame.f_aOp;
                            iPC = frame.m_iPC;
                            break;
                            }
                        }

                    frame = m_frameCurrent = frame.f_framePrev;
                    if (frame == null)
                        {
                        // all done
                        return null;
                        }

                    if (fiber.getStatus() == FiberStatus.Waiting)
                        {
                        return frame;
                        }
                    abOp = frame.f_aOp;
                    iPC = frame.m_iPC;
                    break;

                case Op.R_RETURN_EXCEPTION:
                    frame = frame.f_framePrev;
                    // fall-through

                case Op.R_EXCEPTION:
                    ExceptionHandle hException = frame.m_hException;
                    assert hException != null;

                    while (true)
                        {
                        iPC = frame.findGuard(hException);
                        if (iPC >= 0)
                            {
                            // handled exception; go to the handler
                            m_frameCurrent = frame;
                            abOp = frame.f_aOp;
                            break;
                            }

                        // not handled by this frame
                        Frame framePrev = frame.f_framePrev;
                        if (framePrev == null)
                            {
                            // the frame is a synthetic "proto" frame;
                            // it should process the exception
                            if (frame.m_continuation == null)
                                {
                                // TODO: this should never happen
                                Utils.log("\nProto-frame is missing the continuation: " + hException);
                                }
                            else
                                {
                                frame.m_hException = hException;
                                Frame frameNext = frame.m_continuation.get();
                                if (frameNext != null)
                                    {
                                    frame = m_frameCurrent = frameNext;
                                    abOp = frame.f_aOp;
                                    iPC = frame.m_iPC;
                                    break;
                                    }
                                }

                            return m_frameCurrent = null;
                            }
                        frame = framePrev;
                        }
                    break;

                case Op.R_REPEAT:
                    frame.m_iPC = iPCLast;
                    fiber.setStatus(FiberStatus.Waiting);
                    return frame;

                case Op.R_BLOCK:
                    frame.m_iPC = iPCLast + 1;
                    fiber.setStatus(FiberStatus.Waiting);
                    return frame;

                case Op.R_YIELD:
                    frame.m_iPC = iPCLast + 1;
                    fiber.setStatus(FiberStatus.Yielded);
                    return frame;
                }
            }
        }

    // create a new frame that returns zero or one value into the specified slot
    public Frame createFrame1(Frame framePrev, InvocationTemplate template,
                              ObjectHandle hTarget, ObjectHandle[] ahVar, int iReturn)
        {
        return new Frame(framePrev, template, hTarget, ahVar, iReturn, null);
        }

    public Frame createFrameN(Frame framePrev, InvocationTemplate template,
                             ObjectHandle hTarget, ObjectHandle[] ahVar, int[] aiReturn)
        {
        return new Frame(framePrev, template, hTarget, ahVar, Frame.RET_MULTI, aiReturn);
        }

    // create a "proto"-frame
    protected Frame createServiceEntryFrame(Fiber fiberCaller, int cReturns, Op[] aopNative)
        {
        // TODO: collect the caller's frame proxy

        // create a pseudo frame that has variables to collect
        // the return values
        ObjectHandle[] ahVar = new ObjectHandle[cReturns];

        Fiber fiber = new Fiber(this, fiberCaller);
        Frame frame = new Frame(fiber, aopNative, ahVar, Frame.RET_UNUSED, null);

        for (int iVar = 0; iVar < cReturns; iVar++)
            {
            // create a "dynamic ref" for all return values
            // (see DVar op-code)
            FutureHandle hRef = xFutureRef.makeHandle(new CompletableFuture<>());

            frame.introduceVar(iVar, hRef.f_clazz, null, Frame.VAR_DYNAMIC_REF, hRef);
            }
        return frame;
        }

    // ----- x:Service methods -----

    public boolean isContended()
        {
        return !f_queueMsg.isEmpty() || !f_listSuspended.isEmpty() || m_frameCurrent != null;
        }

    // send and asynchronous "construct service" message to this context
    public CompletableFuture<ServiceHandle> sendConstructRequest(Frame frameCaller,
                ConstructTemplate constructor, TypeComposition clazz, ObjectHandle[] ahArg)
        {
        CompletableFuture<ServiceHandle> future = new CompletableFuture<>();

        addRequest(new ConstructRequest(frameCaller.f_fiber, constructor, clazz, future, ahArg));

        return future.whenComplete((r, x) ->
            {
            if (x != null)
                {
                // the construction failed; we need to kill the service
                f_container.removeServiceContext(this);
                }
            });
        }

    // send and asynchronous "invoke" message with zero or one return value
    public CompletableFuture<ObjectHandle> sendInvoke1Request(Frame frameCaller,
                FunctionHandle hFunction, ObjectHandle[] ahArg, int cReturns)
        {
        CompletableFuture<ObjectHandle> future = cReturns == 0 ? null : new CompletableFuture<>();

        addRequest(new Invoke1Request(frameCaller == null ? null : frameCaller.f_fiber,
                hFunction, ahArg, cReturns, future));

        return future;
        }

    // send and asynchronous "invoke" message with multiple return values
    public CompletableFuture<ObjectHandle[]> sendInvokeNRequest(Frame frameCaller,
                FunctionHandle hFunction, ObjectHandle[] ahArg, int cReturns)
        {
        CompletableFuture<ObjectHandle[]> future = new CompletableFuture<>();

        addRequest(new InvokeNRequest(frameCaller == null ? null : frameCaller.f_fiber,
                hFunction, ahArg, cReturns, future));

        return future;
        }

    // send and asynchronous property operation message
    public CompletableFuture<ObjectHandle> sendProperty01Request(Frame frameCaller,
            PropertyTemplate property, PropertyOperation01 op)
        {
        CompletableFuture<ObjectHandle> future = new CompletableFuture<>();

        addRequest(new PropertyOpRequest(frameCaller == null ? null : frameCaller.f_fiber,
                property, null, 1, future, op));

        return future;
        }

    // send and asynchronous property operation message
    public void sendProperty10Request(Fiber fiberCaller,
            PropertyTemplate property, ObjectHandle hValue, PropertyOperation10 op)
        {
        addRequest(new PropertyOpRequest(fiberCaller, property, hValue, 0, null, op));
        }

    public int callLater(FunctionHandle hFunction, ObjectHandle[] ahArg)
        {
        sendInvoke1Request(null, hFunction, ahArg, 0);
        return Op.R_NEXT;
        }

    public Frame callUnhandledExceptionHandler(Frame frame)
        {
        // TODO:
        Utils.log("Unhandled exception: " + frame.m_hException);
        return null;
        }

    // ----- helpers ------

    // send zero or one results back to the caller
    protected static void sendResponse1(Fiber fiberCaller,
                                        Frame frame, CompletableFuture future)
        {
        ServiceContext contextCaller = fiberCaller.f_context;

        if (frame.m_hException == null)
            {
            CompletableFuture<ObjectHandle> cf =
                    ((FutureHandle) frame.f_ahVar[0]).m_future;

            if (cf.isDone() && !cf.isCompletedExceptionally())
                {
                // common path
                try
                    {
                    contextCaller.respond(new Response(fiberCaller, cf.get(), null, future));
                    }
                catch (Exception e) {}
                }
            else // has not finished yet, or completed exceptionally
                {
                cf.whenComplete((hR, x) ->
                    {
                    if (x == null)
                        {
                        contextCaller.respond(new Response(fiberCaller, hR, null, future));
                        }
                    else
                        {
                        ExceptionHandle hX = ((ExceptionHandle.WrapperException) x).
                                getExceptionHandle();
                        contextCaller.respond(new Response(fiberCaller, null, hX, future));
                        }
                    });
                }
            }
        else
            {
            contextCaller.respond(new Response(fiberCaller, null, frame.m_hException, future));
            }
        }

    @Override
    public String toString()
        {
        return "Service(" + f_sName + ')';
        }

    // --- inner classes

    public static class Fiber
        {
        final ServiceContext f_context;
        final Fiber f_fiberPrev; // a caller's fiber (null for original)

        // the fiber status can only be mutated by the fiber itself
        private FiberStatus m_status;

        // this flag serves a hint that the execution could be resumed;
        // it's set by the responding fiber and cleared reset when the execution resumes
        // the use of this flag is tolerant to the possibility that the "waiting" state times out
        // and the execution resumes before the flag is set; however, we will never lose a
        // "a response has arrived" notification and get stuck waiting
        public volatile boolean m_fResponded;

        // Metrics: the timestamp (in nanos) when the fiber execution has started
        private long m_nanoStarted;

        /**
         * The timeout (timestamp) that this fiber is subject to (optional).
         */
        public long m_ldtTimeout;

        public Fiber(ServiceContext context, Fiber fiberPrev)
            {
            f_context = context;
            f_fiberPrev = fiberPrev;
            m_status = FiberStatus.Initial;
            }

        public FiberStatus getStatus()
            {
            return m_status;
            }

        // called only by this fiber
        public void setStatus(FiberStatus status)
            {
            switch (m_status = status)
                {
                default:
                case Initial:
                    throw new IllegalArgumentException();

                case Running:
                    m_nanoStarted = System.nanoTime();
                    break;

                case Paused:
                case Waiting:
                case Yielded:
                    long cNanos = System.nanoTime() - m_nanoStarted;
                    m_nanoStarted = 0;
                    f_context.m_cRuntimeNanos += cNanos;
                    break;
                }
            }

        public boolean isTimeout()
            {
            return m_ldtTimeout > 0 && System.currentTimeMillis() > m_ldtTimeout;
            }
        }

    public interface Message
        {
        Frame createFrame(ServiceContext context);
        }

    /**
     * Represents an invoke request from one service onto another with zero or one return value.
     */
    public static class ConstructRequest
            implements Message
        {
        private final Fiber f_fiberCaller;
        private final ConstructTemplate f_constructor;
        private final TypeComposition f_clazz;
        private final ObjectHandle[] f_ahArg;
        private final CompletableFuture<ServiceHandle> f_future;

        public ConstructRequest(Fiber fiberCaller, ConstructTemplate constructor,
                                TypeComposition clazz, CompletableFuture<ServiceHandle> future, ObjectHandle[] ahArg)
            {
            f_fiberCaller = fiberCaller;
            f_constructor = constructor;
            f_clazz = clazz;
            f_ahArg = ahArg;
            f_future = future;
            }

        @Override
        public Frame createFrame(ServiceContext context)
            {
            Op opConstruct = new Op()
                {
                public int process(Frame frame, int iPC)
                    {
                    xService service = (xService) f_constructor.getClazzTemplate();

                    return service.constructSync(frame, f_constructor, f_clazz, f_ahArg, 0);
                    }
                };

            Frame frame0 = context.createServiceEntryFrame(f_fiberCaller, 1,
                    new Op[]{opConstruct, Return_0.INSTANCE});

            frame0.m_continuation = () ->
                {
                sendResponse1(f_fiberCaller, frame0, f_future);
                return null;
                };

            return frame0;
            }
        }

    /**
     * Represents an invoke request from one service onto another with zero or one return value.
     */
    public static class Invoke1Request
            implements Message
        {
        private final Fiber f_fiberCaller;
        private final FunctionHandle f_hFunction;
        private final ObjectHandle[] f_ahArg;
        private final int f_cReturns;
        private final CompletableFuture<ObjectHandle> f_future;

        public Invoke1Request(Fiber fiberCaller, FunctionHandle hFunction,
                              ObjectHandle[] ahArg, int cReturns,
                              CompletableFuture<ObjectHandle> future)
            {
            f_fiberCaller = fiberCaller;
            f_hFunction = hFunction;
            f_ahArg = ahArg;
            f_cReturns = cReturns;
            f_future = future;
            }


        @Override
        public Frame createFrame(ServiceContext context)
            {
            Op opCall = new Op()
                {
                public int process(Frame frame, int iPC)
                    {
                    return f_hFunction.call1(frame, f_ahArg, f_cReturns - 1);
                    }
                };

            Frame frame0 = context.createServiceEntryFrame(f_fiberCaller, f_hFunction.getReturnCount(),
                    new Op[] {opCall, Return_0.INSTANCE});


            frame0.m_continuation = () ->
                {
                if (f_cReturns == 0)
                    {
                    if (frame0.m_hException != null)
                        {
                        return context.callUnhandledExceptionHandler(frame0);
                        }
                    }
                else
                    {
                    sendResponse1(f_fiberCaller, frame0, f_future);
                    }
                return null;
                };

            return frame0;
            }
        }

    /**
     * Represents an invoke request from one service onto another with multiple return values.
     */
    public static class InvokeNRequest
            implements Message
        {
        private final Fiber f_fiberCaller;
        private final FunctionHandle f_hFunction;
        private final ObjectHandle[] f_ahArg;
        private final int f_cReturns;
        private final CompletableFuture<ObjectHandle[]> f_future;

        public InvokeNRequest(Fiber fiberCaller, FunctionHandle hFunction,
                             ObjectHandle[] ahArg, int cReturns,
                             CompletableFuture<ObjectHandle[]> future)
            {
            f_fiberCaller = fiberCaller;
            f_hFunction = hFunction;
            f_ahArg = ahArg;
            f_cReturns = cReturns;
            f_future = future;
            }

        @Override
        public Frame createFrame(ServiceContext context)
            {
            // the pseudo-frame's vars are the return values
            int[] aiReturn = new int[f_cReturns];
            for (int i = 0; i < f_cReturns; i++)
                {
                aiReturn[i] = i;
                }

            Op opCall = new Op()
                {
                public int process(Frame frame, int iPC)
                    {
                    return f_hFunction.callN(frame, f_ahArg, aiReturn);
                    }
                };

            Frame frame0 = context.createServiceEntryFrame(f_fiberCaller, f_hFunction.getReturnCount(),
                new Op[] {opCall, Return_0.INSTANCE});

            ServiceContext contextCaller = f_fiberCaller.f_context;
            frame0.m_continuation = () ->
                {
                ExceptionHandle hException = frame0.m_hException;
                if (hException == null)
                    {
                    // TODO: optimize for a case when all futures are complete
                    CompletableFuture<Void>[] acf = new CompletableFuture[f_cReturns];
                    for (int i = 0; i < f_cReturns; i++)
                        {
                        int iRet = i;

                        acf[i] = ((FutureHandle) frame0.f_ahVar[i]).m_future.thenAccept(
                                h -> frame0.f_ahVar[iRet] = h);
                        }

                    CompletableFuture.allOf(acf).whenComplete((none, x) ->
                        {
                        if (x == null)
                            {
                            contextCaller.respond(new Response(f_fiberCaller, frame0.f_ahVar, null, f_future));
                            }
                        else
                            {
                            ExceptionHandle hX = ((ExceptionHandle.WrapperException) x).
                                    getExceptionHandle();
                            contextCaller.respond(new Response(f_fiberCaller, null, hX, f_future));
                            }
                        });
                    }
                else
                    {
                    contextCaller.respond(new Response(f_fiberCaller, null, hException, f_future));
                    }
                return null;
                };

            return frame0;
            }
        }

    /**
     * Represents a property operation request from one service onto another
     * that takes zero or one parameter and returns zero or one value.
     */
    public static class PropertyOpRequest
            implements Message
        {
        private final Fiber f_fiberCaller;
        private final PropertyTemplate f_property;
        private final ObjectHandle f_hValue;
        private final int f_cReturns;
        private final CompletableFuture<ObjectHandle> f_future;
        private final PropertyOperation f_op;

        public PropertyOpRequest(Fiber fiberCaller, PropertyTemplate property,
                                 ObjectHandle hValue, int cReturns,
                                 CompletableFuture<ObjectHandle> future, PropertyOperation op)
            {
            f_fiberCaller = fiberCaller;
            f_property = property;
            f_hValue = hValue;
            f_cReturns = cReturns;
            f_future = future;
            f_op = op;
            }

        @Override
        public Frame createFrame(ServiceContext context)
            {
            int cReturns = f_cReturns;

            Op opCall = new Op()
                {
                public int process(Frame frame, int iPC)
                    {
                    return cReturns == 0
                            ? ((PropertyOperation10) f_op).invoke(frame, context.m_hService, f_property, f_hValue)
                       : f_hValue == null
                            ? ((PropertyOperation01) f_op).invoke(frame, context.m_hService, f_property, 0)
                            : ((PropertyOperation11) f_op).invoke(frame, context.m_hService, f_property, f_hValue, 0);
                    }
                };

            Frame frame0 = context.createServiceEntryFrame(f_fiberCaller, cReturns,
                    new Op[]{opCall, Return_0.INSTANCE});

            frame0.m_continuation = () ->
                {
                if (cReturns == 0)
                    {
                    if (frame0.m_hException != null)
                        {
                        return context.callUnhandledExceptionHandler(frame0);
                        }
                    }
                else
                    {
                    sendResponse1(f_fiberCaller, frame0, f_future);
                    }
                return null;
                };

            return frame0;
            }
        }

    /**
     * Represents a service call return.
     */
    public static class Response<T>
            implements Runnable
        {
        private final Fiber f_fiberCaller;
        private final T f_return;
        private final ExceptionHandle f_hException;
        private final CompletableFuture<T> f_future;

        public Response(Fiber fiberCaller, T returnValue, ExceptionHandle hException,
                        CompletableFuture<T> future)
            {
            f_fiberCaller = fiberCaller;
            f_hException = hException;
            f_return = returnValue;
            f_future = future;
            }

        @Override
        public void run()
            {
            f_fiberCaller.m_fResponded = true;

            if (f_hException == null)
                {
                f_future.complete(f_return);
                }
            else
                {
                f_future.completeExceptionally(f_hException.getException());
                }
            }
        }
    }
