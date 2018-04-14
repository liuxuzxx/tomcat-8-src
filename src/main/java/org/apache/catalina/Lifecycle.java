package org.apache.catalina;


/**
 * Common interface for component life cycle methods.  Catalina components
 * may implement this interface (as well as the appropriate interface(s) for
 * the functionality they support) in order to provide a consistent mechanism
 * to start and stop the component.
 * <br>
 * The valid state transitions for components that support {@link Lifecycle}
 * are:
 * <pre>
 *            start()
 *  -----------------------------
 *  |                           |
 *  | init()                    |
 * NEW -»-- INITIALIZING        |
 * | |           |              |     ------------------«-----------------------
 * | |           |auto          |     |                                        |
 * | |          \|/    start() \|/   \|/     auto          auto         stop() |
 * | |      INITIALIZED --»-- STARTING_PREP --»- STARTING --»- STARTED --»---  |
 * | |         |                                                            |  |
 * | |destroy()|                                                            |  |
 * | --»-----«--    ------------------------«--------------------------------  ^
 * |     |          |                                                          |
 * |     |         \|/          auto                 auto              start() |
 * |     |     STOPPING_PREP ----»---- STOPPING ------»----- STOPPED -----»-----
 * |    \|/                               ^                     |  ^
 * |     |               stop()           |                     |  |
 * |     |       --------------------------                     |  |
 * |     |       |                                              |  |
 * |     |       |    destroy()                       destroy() |  |
 * |     |    FAILED ----»------ DESTROYING ---«-----------------  |
 * |     |                        ^     |                          |
 * |     |     destroy()          |     |auto                      |
 * |     --------»-----------------    \|/                         |
 * |                                 DESTROYED                     |
 * |                                                               |
 * |                            stop()                             |
 * ----»-----------------------------»------------------------------
 *
 * Any state can transition to FAILED.
 *
 * Calling start() while a component is in states STARTING_PREP, STARTING or
 * STARTED has no effect.
 *
 * Calling start() while a component is in state NEW will cause init() to be
 * called immediately after the start() method is entered.
 *
 * Calling stop() while a component is in states STOPPING_PREP, STOPPING or
 * STOPPED has no effect.
 *
 * Calling stop() while a component is in state NEW transitions the component
 * to STOPPED. This is typically encountered when a component fails to start and
 * does not start all its sub-components. When the component is stopped, it will
 * try to stop all sub-components - even those it didn't start.
 *
 * Attempting any other transition will throw {@link LifecycleException}.
 *
 * </pre>
 * The {@link LifecycleEvent}s fired during state changes are defined in the
 * methods that trigger the changed. No {@link LifecycleEvent}s are fired if the
 * attempted transition is not valid.
 * <p>
 * 整个组件的生命周期的一个接口
 * 只要是tomcat的组件都直接或者间接的实现了这个接口。就是记录整个组件生命周期的过程。便于组织我们的操作。也算是模板模式的应用了
 * 基本生命周期如下:init--->start--->stop--->destroy.
 */
public interface Lifecycle {

    /**
     * The LifecycleEvent type for the "component before init" event.
     */
    String BEFORE_INIT_EVENT = "before_init";


    /**
     * The LifecycleEvent type for the "component after init" event.
     */
    String AFTER_INIT_EVENT = "after_init";

    /**
     * The LifecycleEvent type for the "component before start" event.
     */
    String BEFORE_START_EVENT = "before_start";

    /**
     * The LifecycleEvent type for the "component start" event.
     */
    String START_EVENT = "start";

    /**
     * The LifecycleEvent type for the "component after start" event.
     */
    String AFTER_START_EVENT = "after_start";

    /**
     * The LifecycleEvent type for the "component before stop" event.
     */
    String BEFORE_STOP_EVENT = "before_stop";

    /**
     * The LifecycleEvent type for the "component stop" event.
     */
    String STOP_EVENT = "stop";

    /**
     * The LifecycleEvent type for the "component after stop" event.
     */
    String AFTER_STOP_EVENT = "after_stop";

    /**
     * The LifecycleEvent type for the "component before destroy" event.
     */
    String BEFORE_DESTROY_EVENT = "before_destroy";

    /**
     * The LifecycleEvent type for the "component after destroy" event.
     */
    String AFTER_DESTROY_EVENT = "after_destroy";

    /**
     * The LifecycleEvent type for the "periodic" event.
     */
    String PERIODIC_EVENT = "periodic";


    /**
     * The LifecycleEvent type for the "configure_start" event. Used by those
     * components that use a separate component to perform configuration and
     * need to signal when configuration should be performed - usually after
     * {@link #BEFORE_START_EVENT} and before {@link #START_EVENT}.
     */
    String CONFIGURE_START_EVENT = "configure_start";


    /**
     * The LifecycleEvent type for the "configure_stop" event. Used by those
     * components that use a separate component to perform configuration and
     * need to signal when de-configuration should be performed - usually after
     * {@link #STOP_EVENT} and before {@link #AFTER_STOP_EVENT}.
     */
    String CONFIGURE_STOP_EVENT = "configure_stop";

    /**
     * Add a LifecycleEvent listener to this component.
     *
     * @param listener The listener to add
     *                 我猜测这个LifecycleListener可以自由添加，要不然，我们想看看生命周期的过程怎么办
     */
    void addLifecycleListener(LifecycleListener listener);


    /**
     * Get the life cycle listeners associated with this life cycle.
     *
     * @return An array containing the life cycle listeners associated with this
     * life cycle. If this component has no listeners registered, a
     * zero-length array is returned.
     * 不明白tomcat的作者这么钟情于数组，而不是List，我倒是觉得List更加的安全，使用stream也是方便的不得了.
     */
    LifecycleListener[] findLifecycleListeners();


    /**
     * Remove a LifecycleEvent listener from this component.
     *
     * @param listener The listener to remove
     */
    void removeLifecycleListener(LifecycleListener listener);


    /**
     * Prepare the component for starting. This method should perform any
     * initialization required post object creation. The following
     * {@link LifecycleEvent}s will be fired in the following order:
     * <ol>
     * <li>INIT_EVENT: On the successful completion of component
     * initialization.</li>
     * </ol>
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    void init() throws LifecycleException;

    /**
     * Prepare for the beginning of active use of the public methods other than
     * property getters/setters and life cycle methods of this component. This
     * method should be called before any of the public methods other than
     * property getters/setters and life cycle methods of this component are
     * utilized. The following {@link LifecycleEvent}s will be fired in the
     * following order:
     * <ol>
     * <li>BEFORE_START_EVENT: At the beginning of the method. It is as this
     * point the state transitions to
     * {@link LifecycleState#STARTING_PREP}.</li>
     * <li>START_EVENT: During the method once it is safe to call start() for
     * any child components. It is at this point that the
     * state transitions to {@link LifecycleState#STARTING}
     * and that the public methods other than property
     * getters/setters and life cycle methods may be
     * used.</li>
     * <li>AFTER_START_EVENT: At the end of the method, immediately before it
     * returns. It is at this point that the state
     * transitions to {@link LifecycleState#STARTED}.
     * </li>
     * </ol>
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    void start() throws LifecycleException;


    /**
     * Gracefully terminate the active use of the public methods other than
     * property getters/setters and life cycle methods of this component. Once
     * the STOP_EVENT is fired, the public methods other than property
     * getters/setters and life cycle methods should not be used. The following
     * {@link LifecycleEvent}s will be fired in the following order:
     * <ol>
     * <li>BEFORE_STOP_EVENT: At the beginning of the method. It is at this
     * point that the state transitions to
     * {@link LifecycleState#STOPPING_PREP}.</li>
     * <li>STOP_EVENT: During the method once it is safe to call stop() for
     * any child components. It is at this point that the
     * state transitions to {@link LifecycleState#STOPPING}
     * and that the public methods other than property
     * getters/setters and life cycle methods may no longer be
     * used.</li>
     * <li>AFTER_STOP_EVENT: At the end of the method, immediately before it
     * returns. It is at this point that the state
     * transitions to {@link LifecycleState#STOPPED}.
     * </li>
     * </ol>
     * <p>
     * Note that if transitioning from {@link LifecycleState#FAILED} then the
     * three events above will be fired but the component will transition
     * directly from {@link LifecycleState#FAILED} to
     * {@link LifecycleState#STOPPING}, bypassing
     * {@link LifecycleState#STOPPING_PREP}
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that needs to be reported
     */
    void stop() throws LifecycleException;

    /**
     * Prepare to discard the object. The following {@link LifecycleEvent}s will
     * be fired in the following order:
     * <ol>
     * <li>DESTROY_EVENT: On the successful completion of component
     * destruction.</li>
     * </ol>
     *
     * @throws LifecycleException if this component detects a fatal error
     *                            that prevents this component from being used
     */
    void destroy() throws LifecycleException;


    /**
     * Obtain the current state of the source component.
     *
     * @return The current state of the source component.
     */
    LifecycleState getState();


    /**
     * Obtain a textual representation of the current component state. Useful
     * for JMX. The format of this string may vary between point releases and
     * should not be relied upon to determine component state. To determine
     * component state, use {@link #getState()}.
     *
     * @return The name of the current component state.
     */
    String getStateName();


    /**
     * Marker interface used to indicate that the instance should only be used
     * once. Calling {@link #stop()} on an instance that supports this interface
     * will automatically call {@link #destroy()} after {@link #stop()}
     * completes.
     */
    interface SingleUse {
    }
}
