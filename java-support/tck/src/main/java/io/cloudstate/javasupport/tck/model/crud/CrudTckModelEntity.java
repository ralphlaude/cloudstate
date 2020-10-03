/*
 * Copyright 2019 Lightbend Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package io.cloudstate.javasupport.tck.model.crud;

import io.cloudstate.javasupport.Context;
import io.cloudstate.javasupport.ServiceCall;
import io.cloudstate.javasupport.ServiceCallRef;
import io.cloudstate.javasupport.crud.CrudEntity;
import io.cloudstate.javasupport.crud.CommandContext;
import io.cloudstate.javasupport.crud.CommandHandler;
import io.cloudstate.tck.model.crud.Crud;
import io.cloudstate.tck.model.crud.Crud.*;

import java.util.Optional;

@CrudEntity(persistenceId = "crud-tck-model")
public class CrudTckModelEntity {

  private final ServiceCallRef<Request> serviceTwoCall;

  private String state = "";

  public CrudTckModelEntity(Context context) {
    serviceTwoCall =
        context.serviceCallFactory().lookup("cloudstate.tck.model.CrudTwo", "Call", Request.class);
  }

  @CommandHandler
  public Optional<Response> process(Request request, CommandContext<Persisted> context) {
    boolean forwarding = false;
    for (Crud.RequestAction action : request.getActionsList()) {
      switch (action.getActionCase()) {
        case UPDATE:
          state = action.getUpdate().getValue();
          context.updateState(Persisted.newBuilder().setValue(state).build());
          break;
        case DELETE:
          context.deleteState();
          state = "";
          break;
        case FORWARD:
          forwarding = true;
          context.forward(serviceTwoRequest(action.getForward().getId()));
          break;
        case EFFECT:
          Crud.Effect effect = action.getEffect();
          context.effect(serviceTwoRequest(effect.getId()), effect.getSynchronous());
          break;
        case FAIL:
          context.fail(action.getFail().getMessage());
          break;
      }
    }
    return forwarding
        ? Optional.empty()
        : Optional.of(Response.newBuilder().setMessage(state).build());
  }

  private ServiceCall serviceTwoRequest(String id) {
    return serviceTwoCall.createCall(Request.newBuilder().setId(id).build());
  }
}
