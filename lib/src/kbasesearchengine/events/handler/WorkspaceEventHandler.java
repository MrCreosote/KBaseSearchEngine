package kbasesearchengine.events.handler;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;

import com.fasterxml.jackson.core.type.TypeReference;

import kbasesearchengine.common.GUID;
import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventType;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.exceptions.IndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.exceptions.RetriableIndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.UnprocessableEventIndexingException;
import kbasesearchengine.system.StorageObjectType;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.Tuple9;
import us.kbase.common.service.UObject;
import us.kbase.common.service.UnauthorizedException;
import workspace.GetObjectInfo3Params;
import workspace.GetObjectInfo3Results;
import workspace.GetObjects2Params;
import workspace.GetObjects2Results;
import workspace.ListObjectsParams;
import workspace.ObjectData;
import workspace.ObjectIdentity;
import workspace.ObjectSpecification;
import workspace.ProvenanceAction;
import workspace.SubAction;
import workspace.WorkspaceClient;
import workspace.WorkspaceIdentity;

/** A handler for events generated by the workspace service.
 * @author gaprice@lbl.gov
 *
 */
public class WorkspaceEventHandler implements EventHandler {
    
    private final static DateTimeFormatter DATE_PARSER =
            new DateTimeFormatterBuilder()
                .append(DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss"))
                .appendOptional(DateTimeFormat.forPattern(".SSS").getParser())
                .append(DateTimeFormat.forPattern("Z"))
                .toFormatter(); 
    
    //TODO TEST

    /** The storage code for workspace events. */
    public static final String STORAGE_CODE = "WS";
    
    private static final int WS_BATCH_SIZE = 10_000;
    
    private static final TypeReference<List<Tuple11<Long, String, String, String,
            Long, String, Long, String, String, Long, Map<String, String>>>> OBJ_TYPEREF =
                    new TypeReference<List<Tuple11<Long, String, String, String,
                            Long, String, Long, String, String, Long, Map<String, String>>>>() {};

    private static final TypeReference<Tuple9<Long, String, String, String, Long, String,
            String, String, Map<String, String>>> WS_INFO_TYPEREF =
                    new TypeReference<Tuple9<Long, String, String, String, Long, String, String,
                            String,Map<String,String>>>() {};
    
    private final WorkspaceClient ws;
    
    /** Create a handler.
     * @param wsClient a workspace client to use when contacting the workspace service.
     */
    public WorkspaceEventHandler(final WorkspaceClient wsClient) {
        ws = wsClient;
    }
    
    @Override
    public String getStorageCode() {
        return STORAGE_CODE;
    }
    
    @Override
    public SourceData load(final GUID guid, final Path file)
            throws IndexingException, RetriableIndexingException {
        return load(Arrays.asList(guid), file);
    }

    @Override
    public SourceData load(final List<GUID> guids, final Path file)
            throws IndexingException, RetriableIndexingException {
        //TODO CODE check storage code
        // create a new client since we're setting a file for the next response
        // fixes race conditions
        // a clone method would be handy
        final WorkspaceClient wc;
        try {
            wc = new WorkspaceClient(ws.getURL(), ws.getToken());
        } catch (IOException e) {
            throw handleException(e);
        } catch (UnauthorizedException e) {
            throw new FatalIndexingException(e.getMessage(), e);
        }
        wc.setIsInsecureHttpConnectionAllowed(ws.isInsecureHttpConnectionAllowed());
        wc.setStreamingModeOn(true);
        wc._setFileForNextRpcResponse(file.toFile());
        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjects");
        command.put("params", new GetObjects2Params().withObjects(
                Arrays.asList(new ObjectSpecification().withRef(toWSRefPath(guids)))));
        final ObjectData ret;
        try {
            ret = wc.administer(new UObject(command))
                    .asClassInstance(GetObjects2Results.class)
                    .getData().get(0);
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
        // we'll assume here that there's only one provenance action. This may need more thought
        // if that's not true.
        final ProvenanceAction pa = ret.getProvenance().isEmpty() ?
                null : ret.getProvenance().get(0);
        final String creator = ret.getCreator();
        String copier = ret.getInfo().getE6();
        if (ret.getCopied() == null & ret.getCopySourceInaccessible() == 0) {
            copier = null;
        }
        final SourceData.Builder b = SourceData.getBuilder(ret.getData(), ret.getInfo().getE2())
                .withNullableCreator(creator)
                .withNullableCopier(copier);
        if (pa != null) {
            b.withNullableModule(pa.getService())
                    .withNullableMethod(pa.getMethod())
                    .withNullableVersion(pa.getServiceVer());
            /* this is taking specific knowledge about how the KBase execution engine
             * works into account, which I'm not sure is a good idea, but for now it'll do 
             */
            if (pa.getService() != null &&
                    pa.getMethod() != null &&
                    pa.getSubactions() != null &&
                    !pa.getSubactions().isEmpty()) {
                final String modmeth = pa.getService() + "." + pa.getMethod();
                for (final SubAction sa: pa.getSubactions()) {
                    if (modmeth.equals(sa.getName())) {
                        b.withNullableCommitHash(sa.getCommit());
                    }
                }
            }
        }
        return b.build();
    }

    private static IndexingException handleException(final JsonClientException e) {
        if (e instanceof UnauthorizedException) {
            return new FatalIndexingException(e.getMessage(), e);
        }
        if (e.getMessage() == null) {
            return new UnprocessableEventIndexingException(
                    "Null error message from workspace server", e);
        } else if (e.getMessage().toLowerCase().contains("login")) {
            return new FatalIndexingException(
                    "Workspace credentials are invalid: " + e.getMessage(), e);
        } else {
            // this may need to be expanded, some errors may require retries or total failures
            return new UnprocessableEventIndexingException(
                    "Unrecoverable error from workspace on fetching object: " + e.getMessage(),
                    e);
        }
    }

    private static RetriableIndexingException handleException(final IOException e) {
        if (e instanceof ConnectException) {
            return new FatalRetriableIndexingException(e.getMessage(), e);
        }
        return new RetriableIndexingException(e.getMessage(), e);
    }
    
    @Override
    public Map<GUID, String> buildReferencePaths(
            final List<GUID> refpath,
            final Set<GUID> refs) {
        final String refPrefix = buildRefPrefix(refpath);
        return refs.stream().collect(Collectors.toMap(r -> r, r -> refPrefix + r.toRefString()));
    }
    
    @Override
    public Set<ResolvedReference> resolveReferences(
            final List<GUID> refpath,
            final Set<GUID> refs)
            throws RetriableIndexingException, IndexingException {
        // may need to split into batches 
        final String refPrefix = buildRefPrefix(refpath);
        
        final List<GUID> orderedRefs = new ArrayList<>(refs);
        
        final List<ObjectSpecification> getInfoInput = orderedRefs.stream().map(
                ref -> new ObjectSpecification().withRef(refPrefix + ref.toRefString())).collect(
                        Collectors.toList());
        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjectInfo");
        command.put("params", new GetObjectInfo3Params().withObjects(getInfoInput));
        
        final GetObjectInfo3Results res;
        try {
            res = ws.administer(new UObject(command)).asClassInstance(GetObjectInfo3Results.class);
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
        final Set<ResolvedReference> ret = new HashSet<>();
        for (int i = 0; i < orderedRefs.size(); i++) {
            ret.add(createResolvedReference(orderedRefs.get(i), res.getInfos().get(i)));
        }
        return ret;
    }

    private String buildRefPrefix(final List<GUID> refpath) {
        //TODO CODE check storage code
        return refpath == null || refpath.isEmpty() ? "" :
            WorkspaceEventHandler.toWSRefPath(refpath) + ";";
    }
    
    private ResolvedReference createResolvedReference(
            final GUID guid,
            final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                    Long, Map<String, String>> obj) {
        return new ResolvedReference(
                guid,
                new GUID(STORAGE_CODE, Math.toIntExact(obj.getE7()), obj.getE1() + "",
                        Math.toIntExact(obj.getE5()), null, null),
                new StorageObjectType(STORAGE_CODE, obj.getE3().split("-")[0],
                      Integer.parseInt(obj.getE3().split("-")[1].split("\\.")[0])),
                Instant.ofEpochMilli(DATE_PARSER.parseDateTime(obj.getE4()).getMillis()));
    }

    @Override
    public boolean isExpandable(final StoredStatusEvent parentEvent) {
        checkStorageCode(parentEvent);
        return EXPANDABLES.contains(parentEvent.getEvent().getEventType());
        
    };
    
    private static final Set<StatusEventType> EXPANDABLES = new HashSet<>(Arrays.asList(
            StatusEventType.NEW_ALL_VERSIONS,
            StatusEventType.COPY_ACCESS_GROUP,
            StatusEventType.DELETE_ACCESS_GROUP,
            StatusEventType.PUBLISH_ACCESS_GROUP,
            StatusEventType.UNPUBLISH_ACCESS_GROUP));
    
    @Override
    public Iterable<StatusEvent> expand(final StoredStatusEvent eventWID)
            throws IndexingException, RetriableIndexingException {
        checkStorageCode(eventWID);
        final StatusEvent event = eventWID.getEvent();
        if (StatusEventType.NEW_ALL_VERSIONS.equals(event.getEventType())) {
            return handleNewAllVersions(event);
        } else if (StatusEventType.COPY_ACCESS_GROUP.equals(event.getEventType())) {
            return handleNewAccessGroup(event);
        } else if (StatusEventType.DELETE_ACCESS_GROUP.equals(event.getEventType())) {
            return handleDeletedAccessGroup(event);
        } else if (StatusEventType.PUBLISH_ACCESS_GROUP.equals(event.getEventType())) {
            return handlePublishAccessGroup(event, StatusEventType.PUBLISH_ALL_VERSIONS);
        } else if (StatusEventType.UNPUBLISH_ACCESS_GROUP.equals(event.getEventType())) {
            return handlePublishAccessGroup(event, StatusEventType.UNPUBLISH_ALL_VERSIONS);
        } else {
            return Arrays.asList(event);
        }
    }

    private void checkStorageCode(final StoredStatusEvent event) {
        checkStorageCode(event.getEvent().getStorageCode());
    }

    private void checkStorageCode(final String storageCode) {
        if (!STORAGE_CODE.equals(storageCode)) {
            throw new IllegalArgumentException("This handler only accepts "
                    + STORAGE_CODE + "events");
        }
    }

    private Iterable<StatusEvent> handlePublishAccessGroup(
            final StatusEvent event,
            final StatusEventType newType)
            throws RetriableIndexingException, IndexingException {

        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getWorkspaceInfo");
        command.put("params", new WorkspaceIdentity()
                .withId((long) event.getAccessGroupId().get()));
        
        final long objcount;
        try {
            objcount = ws.administer(new UObject(command))
                    .asClassInstance(WS_INFO_TYPEREF).getE5();
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
        return new Iterable<StatusEvent>() {
            
            @Override
            public Iterator<StatusEvent> iterator() {
                return new StupidWorkspaceObjectIterator(event, objcount, newType);
            }
        };
    }

    private Iterable<StatusEvent> handleDeletedAccessGroup(final StatusEvent event) {
        
        return new Iterable<StatusEvent>() {

            @Override
            public Iterator<StatusEvent> iterator() {
                return new StupidWorkspaceObjectIterator(
                        event, Long.parseLong(event.getAccessGroupObjectId().get()),
                        StatusEventType.DELETE_ALL_VERSIONS);
            }
            
        };
    }
    
    /* This is not efficient, but allows parallelizing events by decomposing the event
     * to per object events. That means the parallelization can run on a per object basis.
     */
    private static class StupidWorkspaceObjectIterator implements Iterator<StatusEvent> {

        private final StatusEvent event;
        private final StatusEventType newType;
        private final long maxObjectID;
        private long counter = 0;
        
        public StupidWorkspaceObjectIterator(
                final StatusEvent event,
                final long maxObjectID,
                final StatusEventType newType) {
            this.event = event;
            this.maxObjectID = maxObjectID;
            this.newType = newType;
        }

        @Override
        public boolean hasNext() {
            return counter < maxObjectID;
        }

        @Override
        public StatusEvent next() {
            if (counter >= maxObjectID) {
                throw new NoSuchElementException();
            }
            return StatusEvent.getBuilder(STORAGE_CODE, event.getTimestamp(), newType)
                    .withNullableAccessGroupID(event.getAccessGroupId().get())
                    .withNullableObjectID(++counter + "")
                    .build();
        }
        
    }

    private Iterable<StatusEvent> handleNewAccessGroup(final StatusEvent event) {
        return new Iterable<StatusEvent>() {

            @Override
            public Iterator<StatusEvent> iterator() {
                return new WorkspaceIterator(ws, event);
            }
            
        };
    }
    
    private static class WorkspaceIterator implements Iterator<StatusEvent> {
        
        private final WorkspaceClient ws;
        private final StatusEvent sourceEvent;
        private final int accessGroupId;
        private long processedObjs = 0;
        private LinkedList<StatusEvent> queue = new LinkedList<>();

        public WorkspaceIterator(final WorkspaceClient ws, final StatusEvent sourceEvent) {
            this.ws = ws;
            this.sourceEvent = sourceEvent;
            this.accessGroupId = sourceEvent.getAccessGroupId().get();
            fillQueue();
        }

        @Override
        public boolean hasNext() {
            return !queue.isEmpty();
        }

        @Override
        public StatusEvent next() {
            if (queue.isEmpty()) {
                throw new NoSuchElementException();
            }
            final StatusEvent event = queue.removeFirst();
            if (queue.isEmpty()) {
                fillQueue();
            }
            return event;
        }

        private void fillQueue() {
            // as of 0.7.2 if only object id filters are used, workspace will sort by
            // ws asc, obj id asc, ver dec
            
            final ArrayList<StatusEvent> events;
            final Map<String, Object> command = new HashMap<>();
            command.put("command", "listObjects");
            command.put("params", new ListObjectsParams()
                    .withIds(Arrays.asList((long) accessGroupId))
                    .withMinObjectID((long) processedObjs + 1)
                    .withShowHidden(1L)
                    .withShowAllVersions(1L));
            try {
                events = buildEvents(sourceEvent, ws.administer(new UObject(command))
                        .asClassInstance(OBJ_TYPEREF));
            } catch (IOException e) {
                throw new RetriableIndexingExceptionUncheckedWrapper(handleException(e));
            } catch (JsonClientException e) {
                throw new IndexingExceptionUncheckedWrapper(handleException(e));
            }
            if (events.isEmpty()) {
                return;
            }
            // might want to do something smarter about the extra parse at some point
            final long first = Long.parseLong(events.get(0).getAccessGroupObjectId().get());
            final StatusEvent lastEv = events.get(events.size() - 1);
            long last = Long.parseLong(lastEv.getAccessGroupObjectId().get());
            // it cannot be true that there were <10K objects and the last object returned's
            // version was != 1
            if (first == last && events.size() == WS_BATCH_SIZE &&
                    lastEv.getVersion().get() != 1) {
                //holy poopsnacks, a > 10K version object
                queue.addAll(events);
                for (int i = lastEv.getVersion().get(); i > 1; i =- WS_BATCH_SIZE) {
                    fillQueueWithVersions(first, i - WS_BATCH_SIZE, i);
                }
            } else {
                // could be smarter about this later, rather than throwing away all the versions of
                // the last object
                // not too many objects will have enough versions to matter
                if (lastEv.getVersion().get() != 1) {
                    last--;
                }
                for (final StatusEvent e: events) {
                    if (Long.parseLong(e.getAccessGroupObjectId().get()) > last) { // *&@ parse
                        break;
                    }
                    queue.add(e);
                }
            }
            processedObjs = last;
        }

        // startVersion = inclusive, endVersion = exclusive
        private void fillQueueWithVersions(
                final long objectID,
                int startVersion,
                final int endVersion) {
            if (startVersion < 1) {
                startVersion = 1;
            }
            final List<ObjectSpecification> objs = new LinkedList<>();
            for (int ver = startVersion; ver < endVersion; ver++) {
                objs.add(new ObjectSpecification()
                        .withWsid((long) accessGroupId)
                        .withObjid(objectID)
                        .withVer((long) ver));
            }
            final Map<String, Object> command = new HashMap<>();
            command.put("command", "getObjectInfo");
            command.put("params", new GetObjectInfo3Params().withObjects(objs));
            try {
                queue.addAll(buildEvents(sourceEvent, ws.administer(new UObject(command))
                        .asClassInstance(GetObjectInfo3Results.class).getInfos()));
            } catch (IOException e) {
                throw new RetriableIndexingExceptionUncheckedWrapper(handleException(e));
            } catch (JsonClientException e) {
                throw new IndexingExceptionUncheckedWrapper(handleException(e));
            }
        }
    }

    private Iterable<StatusEvent> handleNewAllVersions(final StatusEvent event)
            throws IndexingException, RetriableIndexingException {
        final long objid;
        try {
            objid = Long.parseLong(event.getAccessGroupObjectId().get());
        } catch (NumberFormatException ne) {
            throw new UnprocessableEventIndexingException("Illegal workspace object id: " +
                    event.getAccessGroupObjectId());
        }
        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjectHistory");
        command.put("params", new ObjectIdentity()
                .withWsid((long) event.getAccessGroupId().get())
                .withObjid(objid));
        try {
            return buildEvents(event, ws.administer(new UObject(command))
                    .asClassInstance(OBJ_TYPEREF));
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
    }

    private static ArrayList<StatusEvent> buildEvents(
            final StatusEvent originalEvent,
            final List<Tuple11<Long, String, String, String, Long, String, Long, String,
                    String, Long, Map<String, String>>> objects) {
        final ArrayList<StatusEvent> events = new ArrayList<>();
        for (final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                Long, Map<String, String>> obj: objects) {
            events.add(buildEvent(originalEvent, obj));
        }
        return events;
    }
    
    private static StatusEvent buildEvent(
            final StatusEvent origEvent,
            final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                    Long, Map<String, String>> obj) {
        final StorageObjectType storageObjectType = new StorageObjectType(
                STORAGE_CODE, obj.getE3().split("-")[0],
                Integer.parseInt(obj.getE3().split("-")[1].split("\\.")[0]));
        return StatusEvent.getBuilder(
                storageObjectType,
                Instant.ofEpochMilli(DATE_PARSER.parseDateTime(obj.getE4()).getMillis()),
                StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(origEvent.getAccessGroupId().get())
                .withNullableObjectID(obj.getE1() + "")
                .withNullableVersion(Math.toIntExact(obj.getE5()))
                .withNullableisPublic(origEvent.isPublic().get())
                .build();
    }
    
    private static String toWSRefPath(final List<GUID> objectRefPath) {
        final List<String> refpath = new LinkedList<>();
        for (final GUID g: objectRefPath) {
            if (!g.getStorageCode().equals("WS")) {
                throw new IllegalArgumentException(String.format(
                        "GUID %s is not a workspace object", g));
            }
            refpath.add(g.getAccessGroupId() + "/" + g.getAccessGroupObjectId() + "/" +
                    g.getVersion());
        }
        return String.join(";", refpath);
    }

}
