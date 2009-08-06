package org.chromium.sdk.internal.tools.v8;

import org.chromium.sdk.Breakpoint;
import org.chromium.sdk.BrowserTab;
import org.chromium.sdk.JavascriptVm.BreakpointCallback;
import org.chromium.sdk.internal.DebugContextImpl;
import org.chromium.sdk.internal.JsonUtil;
import org.chromium.sdk.internal.DebugContextImpl.SendingType;
import org.chromium.sdk.internal.tools.v8.request.DebuggerMessageFactory;
import org.json.simple.JSONObject;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class BreakpointManager {
  /**
   * This map shall contain only breakpoints with valid IDs.
   */
  private final Map<Long, Breakpoint> idToBreakpoint = new HashMap<Long, Breakpoint>();
  
  private final DebugContextImpl context;

  /** The breakpoints hit before suspending. */
  private volatile Collection<Breakpoint> breakpointsHit;
  
  
  public BreakpointManager(DebugContextImpl context) {
    this.context = context;
  }

  public void setBreakpoint(final Breakpoint.Type type, String target, int line, int position,
      final boolean enabled, final String condition, final int ignoreCount,
      final BrowserTab.BreakpointCallback callback) {
    context.sendMessage(
        SendingType.ASYNC_IMMEDIATE,
        DebuggerMessageFactory.setBreakpoint(type, target, toNullableInteger(line),
            toNullableInteger(position), enabled, condition,
            toNullableInteger(ignoreCount)),
        callback == null
            ? null
            : new V8CommandProcessor.V8HandlerCallback() {
              public void messageReceived(JSONObject response) {
                if (JsonUtil.isSuccessful(response)) {
                  JSONObject body = JsonUtil.getBody(response);
                  long id = JsonUtil.getAsLong(body, V8Protocol.BODY_BREAKPOINT);

                  final BreakpointImpl breakpoint =
                      new BreakpointImpl(type, id, enabled, ignoreCount,
                          condition, BreakpointManager.this);

                  callback.success(breakpoint);
                  idToBreakpoint.put(breakpoint.getId(), breakpoint);
                } else {
                  callback.failure(JsonUtil.getAsString(response, V8Protocol.KEY_MESSAGE));
                }
              }
              public void failure(String message) {
                if (callback != null) {
                  callback.failure(message);
                }
              }
            });
  }
  
  public Breakpoint getBreakpoint(Long id) {
    return idToBreakpoint.get(id);
  }

  public void clearBreakpoint(
      final BreakpointImpl breakpointImpl, final BreakpointCallback callback) {
    long id = breakpointImpl.getId();
    if (id == Breakpoint.INVALID_ID) {
      return;
    }
    idToBreakpoint.remove(id);
    context.sendMessage(
        SendingType.ASYNC_IMMEDIATE,
        DebuggerMessageFactory.clearBreakpoint(breakpointImpl),
        new V8CommandProcessor.V8HandlerCallback() {
          public void messageReceived(JSONObject response) {
            if (JsonUtil.isSuccessful(response)) {
              if (callback != null) {
                callback.success(null);
              }
            } else {
              if (callback != null) {
                callback.failure(JsonUtil.getAsString(response, V8Protocol.KEY_MESSAGE));
              }
            }
          }
          public void failure(String message) {
            if (callback != null) {
              callback.failure(message);
            }
          }
        });
  }

  public void changeBreakpoint(final BreakpointImpl breakpointImpl,
      final BreakpointCallback callback) {
    context.sendMessage(
        SendingType.ASYNC_IMMEDIATE,
        DebuggerMessageFactory.changeBreakpoint(breakpointImpl),
        new V8CommandProcessor.V8HandlerCallback() {
          public void messageReceived(JSONObject response) {
            if (callback != null) {
              if (JsonUtil.isSuccessful(response)) {
                  callback.success(breakpointImpl);
              } else {
                  callback.failure(JsonUtil.getAsString(response, V8Protocol.KEY_MESSAGE));
              }
            }
          }
          public void failure(String message) {
            if (callback != null) {
              callback.failure(message);
            }
          }
        });
  }

  /**
   * Stores the breakpoints associated with V8 suspension event (empty if an
   * exception or a step end).
   *
   * @param breakpointsHit the breakpoints that were hit
   */
  public void onBreakpointsHit(Collection<? extends Breakpoint> breakpointsHit) {
    this.breakpointsHit = Collections.unmodifiableCollection(breakpointsHit);
  }

  public Collection<Breakpoint> getBreakpointsHit() {
    return breakpointsHit != null
        ? breakpointsHit
        : Collections.<Breakpoint> emptySet();
  }

  private static Integer toNullableInteger(int value) {
    return value == Breakpoint.EMPTY_VALUE
        ? null
        : value;
  }
}