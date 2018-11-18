package org.apache.coyote;

public interface ActionHook {

    void action(ActionCode actionCode, Object param);
}
