/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.tngp.broker.incident;

import org.agrona.DirectBuffer;
import org.camunda.tngp.broker.Constants;
import org.camunda.tngp.broker.incident.data.ErrorType;
import org.camunda.tngp.broker.incident.data.IncidentEvent;
import org.camunda.tngp.broker.incident.data.IncidentEventType;
import org.camunda.tngp.broker.logstreams.BrokerEventMetadata;
import org.camunda.tngp.broker.workflow.data.WorkflowInstanceEvent;
import org.camunda.tngp.logstreams.log.LogStream;
import org.camunda.tngp.logstreams.log.LogStreamWriter;
import org.camunda.tngp.logstreams.log.LogStreamWriterImpl;
import org.camunda.tngp.logstreams.log.LoggedEvent;
import org.camunda.tngp.logstreams.processor.StreamProcessorErrorHandler;
import org.camunda.tngp.protocol.clientapi.EventType;

public class IncidentStreamProcessorErrorHandler implements StreamProcessorErrorHandler
{
    private final int streamProcessorId;

    private final DirectBuffer sourceStreamTopicName;
    private final int sourceStreamPartitionId;

    private final LogStreamWriter logStreamWriter;

    private final BrokerEventMetadata incidentEventMetadata = new BrokerEventMetadata();
    private final IncidentEvent incidentEvent = new IncidentEvent();

    private final BrokerEventMetadata failureEventMetadata = new BrokerEventMetadata();
    private final WorkflowInstanceEvent workflowInstanceEvent = new WorkflowInstanceEvent();

    public IncidentStreamProcessorErrorHandler(LogStream targetLogStream, LogStream sourceStream, int streamProcessorId)
    {
        this.streamProcessorId = streamProcessorId;
        this.sourceStreamTopicName = sourceStream.getTopicName();
        this.sourceStreamPartitionId = sourceStream.getPartitionId();

        this.logStreamWriter = new LogStreamWriterImpl(targetLogStream);
    }

    @Override
    public int onError(LoggedEvent failureEvent, Exception error)
    {
        int result = RESULT_REJECT;

        if (error instanceof DummyException)
        {
            result = handleDummyException(failureEvent, error);
        }

        return result;
    }

    private int handleDummyException(LoggedEvent failureEvent, Exception error)
    {
        incidentEventMetadata.reset();
        incidentEventMetadata
            .protocolVersion(Constants.PROTOCOL_VERSION)
            .eventType(EventType.INCIDENT_EVENT);

        incidentEvent.reset();
        incidentEvent
            .setEventType(IncidentEventType.CREATE)
            .setErrorType(ErrorType.DUMMY_ERROR)
            .setErrorMessage(error.getMessage())
            .setFailureEventTopicName(sourceStreamTopicName)
            .setFailureEventPartitionId(sourceStreamPartitionId)
            .setFailureEventPosition(failureEvent.getPosition());

        failureEventMetadata.reset();
        failureEvent.readMetadata(failureEventMetadata);

        if (failureEventMetadata.getEventType() == EventType.WORKFLOW_EVENT)
        {
            workflowInstanceEvent.reset();
            failureEvent.readValue(workflowInstanceEvent);

            incidentEvent
                .setBpmnProcessId(workflowInstanceEvent.getBpmnProcessId())
                .setWorkflowInstanceKey(workflowInstanceEvent.getWorkflowInstanceKey())
                .setActivityId(workflowInstanceEvent.getActivityId());
        }

        final long position = logStreamWriter
                .producerId(streamProcessorId)
                .sourceEvent(sourceStreamTopicName, sourceStreamPartitionId, failureEvent.getPosition())
                .positionAsKey()
                .metadataWriter(incidentEventMetadata)
                .valueWriter(incidentEvent)
                .tryWrite();

        return position > 0 ? RESULT_SUCCESS : RESULT_FAILURE;
    }

}
