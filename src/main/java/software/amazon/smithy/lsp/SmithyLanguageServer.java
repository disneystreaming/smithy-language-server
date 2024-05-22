/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 *  http://aws.amazon.com/apache2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package software.amazon.smithy.lsp;

import static java.util.concurrent.CompletableFuture.completedFuture;

import com.google.gson.JsonObject;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import org.eclipse.lsp4j.CodeAction;
import org.eclipse.lsp4j.CodeActionOptions;
import org.eclipse.lsp4j.CodeActionParams;
import org.eclipse.lsp4j.Command;
import org.eclipse.lsp4j.CompletionItem;
import org.eclipse.lsp4j.CompletionList;
import org.eclipse.lsp4j.CompletionOptions;
import org.eclipse.lsp4j.CompletionParams;
import org.eclipse.lsp4j.DefinitionParams;
import org.eclipse.lsp4j.Diagnostic;
import org.eclipse.lsp4j.DiagnosticSeverity;
import org.eclipse.lsp4j.DidChangeConfigurationParams;
import org.eclipse.lsp4j.DidChangeTextDocumentParams;
import org.eclipse.lsp4j.DidChangeWatchedFilesParams;
import org.eclipse.lsp4j.DidCloseTextDocumentParams;
import org.eclipse.lsp4j.DidOpenTextDocumentParams;
import org.eclipse.lsp4j.DidSaveTextDocumentParams;
import org.eclipse.lsp4j.DocumentFormattingParams;
import org.eclipse.lsp4j.DocumentSymbol;
import org.eclipse.lsp4j.DocumentSymbolParams;
import org.eclipse.lsp4j.FileChangeType;
import org.eclipse.lsp4j.FileEvent;
import org.eclipse.lsp4j.Hover;
import org.eclipse.lsp4j.HoverParams;
import org.eclipse.lsp4j.InitializeParams;
import org.eclipse.lsp4j.InitializeResult;
import org.eclipse.lsp4j.InitializedParams;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.LocationLink;
import org.eclipse.lsp4j.MessageParams;
import org.eclipse.lsp4j.MessageType;
import org.eclipse.lsp4j.ProgressParams;
import org.eclipse.lsp4j.PublishDiagnosticsParams;
import org.eclipse.lsp4j.Range;
import org.eclipse.lsp4j.Registration;
import org.eclipse.lsp4j.RegistrationParams;
import org.eclipse.lsp4j.ServerCapabilities;
import org.eclipse.lsp4j.SymbolInformation;
import org.eclipse.lsp4j.SymbolKind;
import org.eclipse.lsp4j.TextDocumentContentChangeEvent;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.eclipse.lsp4j.TextDocumentSyncKind;
import org.eclipse.lsp4j.TextEdit;
import org.eclipse.lsp4j.Unregistration;
import org.eclipse.lsp4j.UnregistrationParams;
import org.eclipse.lsp4j.WorkDoneProgressBegin;
import org.eclipse.lsp4j.WorkDoneProgressEnd;
import org.eclipse.lsp4j.jsonrpc.CompletableFutures;
import org.eclipse.lsp4j.jsonrpc.messages.Either;
import org.eclipse.lsp4j.services.LanguageClient;
import org.eclipse.lsp4j.services.LanguageClientAware;
import org.eclipse.lsp4j.services.LanguageServer;
import org.eclipse.lsp4j.services.TextDocumentService;
import org.eclipse.lsp4j.services.WorkspaceService;
import software.amazon.smithy.lsp.codeactions.SmithyCodeActions;
import software.amazon.smithy.lsp.diagnostics.DetachedDiagnostics;
import software.amazon.smithy.lsp.diagnostics.VersionDiagnostics;
import software.amazon.smithy.lsp.document.Document;
import software.amazon.smithy.lsp.document.DocumentParser;
import software.amazon.smithy.lsp.document.DocumentShape;
import software.amazon.smithy.lsp.ext.LspLog;
import software.amazon.smithy.lsp.ext.serverstatus.OpenProject;
import software.amazon.smithy.lsp.ext.serverstatus.ServerStatus;
import software.amazon.smithy.lsp.ext.serverstatus.ServerStatusParams;
import software.amazon.smithy.lsp.handler.CompletionHandler;
import software.amazon.smithy.lsp.handler.DefinitionHandler;
import software.amazon.smithy.lsp.handler.FileWatcherRegistrationHandler;
import software.amazon.smithy.lsp.handler.HoverHandler;
import software.amazon.smithy.lsp.project.Project;
import software.amazon.smithy.lsp.project.ProjectConfigLoader;
import software.amazon.smithy.lsp.project.ProjectLoader;
import software.amazon.smithy.lsp.project.ProjectManager;
import software.amazon.smithy.lsp.project.SmithyFile;
import software.amazon.smithy.lsp.protocol.LocationAdapter;
import software.amazon.smithy.lsp.protocol.PositionAdapter;
import software.amazon.smithy.lsp.protocol.RangeAdapter;
import software.amazon.smithy.lsp.protocol.UriAdapter;
import software.amazon.smithy.lsp.util.Result;
import software.amazon.smithy.model.SourceLocation;
import software.amazon.smithy.model.selector.Selector;
import software.amazon.smithy.model.shapes.Shape;
import software.amazon.smithy.model.validation.Severity;
import software.amazon.smithy.model.validation.ValidationEvent;
import software.amazon.smithy.utils.IoUtils;

public class SmithyLanguageServer implements
        LanguageServer, LanguageClientAware, SmithyProtocolExtensions, WorkspaceService, TextDocumentService {
    private static final Logger LOGGER = Logger.getLogger(SmithyLanguageServer.class.getName());
    private static final ServerCapabilities CAPABILITIES;

    static {
        ServerCapabilities capabilities = new ServerCapabilities();
        capabilities.setTextDocumentSync(TextDocumentSyncKind.Incremental);
        capabilities.setCodeActionProvider(new CodeActionOptions(SmithyCodeActions.all()));
        capabilities.setDefinitionProvider(true);
        capabilities.setDeclarationProvider(true);
        capabilities.setCompletionProvider(new CompletionOptions(true, null));
        capabilities.setHoverProvider(true);
        capabilities.setDocumentFormattingProvider(true);
        capabilities.setDocumentSymbolProvider(true);
        CAPABILITIES = capabilities;
    }

    private SmithyLanguageClient client;
    private final ProjectManager projects = new ProjectManager();
    private final DocumentLifecycleManager lifecycleManager = new DocumentLifecycleManager();
    private Severity minimumSeverity = Severity.WARNING;
    private boolean onlyReloadOnSave = false;

    public SmithyLanguageServer() {
    }

    SmithyLanguageServer(LanguageClient client, Project project) {
        this.client = new SmithyLanguageClient(client);
        this.projects.updateMainProject(project);
    }

    SmithyLanguageClient getClient() {
        return this.client;
    }

    Project getProject() {
        return projects.getMainProject();
    }

    ProjectManager getProjects() {
        return projects;
    }

    DocumentLifecycleManager getLifecycleManager() {
        return this.lifecycleManager;
    }

    @Override
    public void connect(LanguageClient client) {
        LOGGER.info("Connect");
        Properties props = new Properties();
        String message = "smithy-language-server";
        try {
            props.load(SmithyLanguageServer.class.getClassLoader().getResourceAsStream("version.properties"));
            message += " version " + props.getProperty("version");
        } catch (Exception ignored) {
        }
        this.client = new SmithyLanguageClient(client);
        this.client.info(message + " started.");
    }

    @Override
    public CompletableFuture<InitializeResult> initialize(InitializeParams params) {
        LOGGER.info("Initialize");

        // TODO: Use this to manage shutdown if the parent process exits, after upgrading jdk
        // Optional.ofNullable(params.getProcessId())
        //         .flatMap(ProcessHandle::of)
        //         .ifPresent(processHandle -> {
        //             processHandle.onExit().thenRun(this::exit);
        //         });

        // TODO: Replace with a Gson Type Adapter if more config options are added beyond `logToFile`.
        Object initializationOptions = params.getInitializationOptions();
        if (initializationOptions instanceof JsonObject) {
            JsonObject jsonObject = (JsonObject) initializationOptions;
            if (jsonObject.has("logToFile")) {
                String setting = jsonObject.get("logToFile").getAsString();
                if (setting.equals("enabled")) {
                    LspLog.enable();
                }
            }
            if (jsonObject.has("diagnostics.minimumSeverity")) {
                String configuredMinimumSeverity = jsonObject.get("diagnostics.minimumSeverity").getAsString();
                Optional<Severity> severity = Severity.fromString(configuredMinimumSeverity);
                if (severity.isPresent()) {
                    this.minimumSeverity = severity.get();
                } else {
                    client.error("Invalid value for 'diagnostics.minimumSeverity': " + configuredMinimumSeverity
                            + ".\nMust be one of 'NOTE', 'WARNING', 'DANGER', 'ERROR'");
                }
            }
            if (jsonObject.has("onlyReloadOnSave")) {
                this.onlyReloadOnSave = jsonObject.get("onlyReloadOnSave").getAsBoolean();
                client.info("Configured only reload on save: " + this.onlyReloadOnSave);
            }
        }

        Path root = null;
        // TODO: Handle multiple workspaces
        if (params.getWorkspaceFolders() != null && !params.getWorkspaceFolders().isEmpty()) {
            String uri = params.getWorkspaceFolders().get(0).getUri();
            root = Paths.get(URI.create(uri));
        } else if (params.getRootUri() != null) {
            String uri = params.getRootUri();
            root = Paths.get(URI.create(uri));
        } else if (params.getRootPath() != null) {
            String uri = params.getRootPath();
            root = Paths.get(URI.create(uri));
        }

        if (root != null) {
            // TODO: Support this for other tasks. Need to create a progress token with the client
            //  through createProgress.
            Either<String, Integer> workDoneProgressToken = params.getWorkDoneToken();
            if (workDoneProgressToken != null) {
                WorkDoneProgressBegin notification = new WorkDoneProgressBegin();
                notification.setTitle("Initializing");
                client.notifyProgress(new ProgressParams(workDoneProgressToken, Either.forLeft(notification)));
            }

            tryInitProject(root);

            if (workDoneProgressToken != null) {
                WorkDoneProgressEnd notification = new WorkDoneProgressEnd();
                client.notifyProgress(new ProgressParams(workDoneProgressToken, Either.forLeft(notification)));
            }
        }

        LOGGER.info("Done initialize");
        return completedFuture(new InitializeResult(CAPABILITIES));
    }

    private void tryInitProject(Path root) {
        LOGGER.info("Initializing project at " + root);
        lifecycleManager.cancelAllTasks();
        Result<Project, List<Exception>> loadResult = ProjectLoader.load(root);
        if (loadResult.isOk()) {
            projects.updateMainProject(loadResult.unwrap());
            LOGGER.info("Initialized project at " + root);
            // TODO: If this is a project reload, there are open files which need to have updated diagnostics reported.
        } else {
            LOGGER.severe("Init project failed");
            // TODO: Maybe we just start with this anyways by default, and then add to it
            //  if we find a smithy-build.json, etc.
            projects.updateMainProject(Project.empty(root));

            String baseMessage = "Failed to load Smithy project at " + root;
            StringBuilder errorMessage = new StringBuilder(baseMessage).append(":");
            for (Exception error : loadResult.unwrapErr()) {
                errorMessage.append(System.lineSeparator());
                errorMessage.append('\t');
                errorMessage.append(error.getMessage());
            }
            client.error(errorMessage.toString());

            String showMessage = baseMessage + ". Check server logs to find out what went wrong.";
            client.showMessage(new MessageParams(MessageType.Error, showMessage));
        }
    }

    private CompletableFuture<Void> registerSmithyFileWatchers() {
        Project project = projects.getMainProject();
        List<Registration> registrations = FileWatcherRegistrationHandler.getSmithyFileWatcherRegistrations(project);
        return client.registerCapability(new RegistrationParams(registrations));
    }

    private CompletableFuture<Void> unregisterSmithyFileWatchers() {
        List<Unregistration> unregistrations = FileWatcherRegistrationHandler.getSmithyFileWatcherUnregistrations();
        return client.unregisterCapability(new UnregistrationParams(unregistrations));
    }

    @Override
    public void initialized(InitializedParams params) {
        List<Registration> registrations = FileWatcherRegistrationHandler.getBuildFileWatcherRegistrations();
        client.registerCapability(new RegistrationParams(registrations));
        registerSmithyFileWatchers();
    }

    @Override
    public WorkspaceService getWorkspaceService() {
        return this;
    }

    @Override
    public TextDocumentService getTextDocumentService() {
        return this;
    }

    @Override
    public CompletableFuture<Object> shutdown() {
        // TODO: Cancel all in-progress requests
        return completedFuture(new Object());
    }

    @Override
    public void exit() {
        System.exit(0);
    }

    @Override
    public CompletableFuture<String> jarFileContents(TextDocumentIdentifier textDocumentIdentifier) {
        LOGGER.info("JarFileContents");
        String uri = textDocumentIdentifier.getUri();
        Project project = projects.getProject(uri);
        Document document = project.getDocument(uri);
        if (document != null) {
            return completedFuture(document.copyText());
        } else {
            // Technically this can throw if the uri is invalid
            return completedFuture(IoUtils.readUtf8Url(UriAdapter.jarUrl(uri)));
        }
    }

    // TODO: This doesn't really work for multiple projects
    @Override
    public CompletableFuture<List<? extends Location>> selectorCommand(SelectorParams selectorParams) {
        LOGGER.info("SelectorCommand");
        Selector selector;
        try {
            selector = Selector.parse(selectorParams.getExpression());
        } catch (Exception e) {
            LOGGER.info("Invalid selector");
            // TODO: Respond with error somehow
            return completedFuture(Collections.emptyList());
        }

        Project project = projects.getMainProject();
        // TODO: Might also want to tell user if the model isn't loaded
        // TODO: Use proper location (source is just a point)
        return completedFuture(project.getModelResult().getResult()
                .map(selector::select)
                .map(shapes -> shapes.stream()
                        .map(Shape::getSourceLocation)
                        .map(LocationAdapter::fromSource)
                        .collect(Collectors.toList()))
                .orElse(Collections.emptyList()));
    }

    @Override
    public CompletableFuture<ServerStatus> serverStatus(ServerStatusParams params) {
        OpenProject openProject = new OpenProject(
                UriAdapter.toUri(projects.getMainProject().getRoot().toString()),
                projects.getMainProject().getSmithyFiles().keySet().stream()
                        .map(UriAdapter::toUri)
                        .collect(Collectors.toList()),
                false);

        List<OpenProject> openProjects = new ArrayList<>();
        openProjects.add(openProject);

        for (Map.Entry<String, Project> entry : projects.getDetachedProjects().entrySet()) {
            openProjects.add(new OpenProject(
                    entry.getKey(),
                    Collections.singletonList(entry.getKey()),
                    true));
        }

        return completedFuture(new ServerStatus(openProjects));
    }

    @Override
    public void didChangeWatchedFiles(DidChangeWatchedFilesParams params) {
        LOGGER.info("DidChangeWatchedFiles");
        // Smithy files were added or deleted to watched sources/imports (specified by smithy-build.json),
        // or the smithy-build.json itself was changed
        List<String> createdSmithyFiles = new ArrayList<>();
        List<String> deletedSmithyFiles = new ArrayList<>();
        boolean changedBuildFiles = false;
        for (FileEvent event : params.getChanges()) {
            String changedUri = event.getUri();
            if (changedUri.endsWith(".smithy")) {
                if (event.getType().equals(FileChangeType.Created)) {
                    createdSmithyFiles.add(changedUri);
                } else if (event.getType().equals(FileChangeType.Deleted)) {
                    deletedSmithyFiles.add(changedUri);
                }
            } else if (changedUri.endsWith(ProjectConfigLoader.SMITHY_BUILD)
                    || changedUri.endsWith(ProjectConfigLoader.SMITHY_PROJECT)) {
                changedBuildFiles = true;
            } else {
                for (String extFile : ProjectConfigLoader.SMITHY_BUILD_EXTS) {
                    if (changedUri.endsWith(extFile)) {
                        changedBuildFiles = true;
                        break;
                    }
                }
            }
        }

        // TODO: Handle files being moved into projects from detached. Will need
        //  to be able to load project with files managed by the client.
        if (changedBuildFiles) {
            client.info("Build files changed, reloading project");
            // TODO: Handle more granular updates to build files.
            tryInitProject(projects.getMainProject().getRoot());
        } else {
            client.info("Project files changed, adding files "
                        + createdSmithyFiles + " and removing files " + deletedSmithyFiles);
            projects.getMainProject().updateFiles(createdSmithyFiles, deletedSmithyFiles);
        }

        // TODO: Update watchers based on specific changes
        unregisterSmithyFileWatchers().thenRun(this::registerSmithyFileWatchers);
    }

    @Override
    public void didChangeConfiguration(DidChangeConfigurationParams params) {
    }

    @Override
    public void didChange(DidChangeTextDocumentParams params) {
        LOGGER.info("DidChange");

        if (params.getContentChanges().isEmpty()) {
            LOGGER.info("Received empty DidChange");
            return;
        }

        String uri = params.getTextDocument().getUri();

        lifecycleManager.cancelTask(uri);

        Project project = projects.getProject(uri);
        Document document = project.getDocument(uri);
        if (document == null) {
            client.error("Attempted to change document the server isn't tracking: " + uri);
            return;
        }

        for (TextDocumentContentChangeEvent contentChangeEvent : params.getContentChanges()) {
            if (contentChangeEvent.getRange() != null) {
                document.applyEdit(contentChangeEvent.getRange(), contentChangeEvent.getText());
            } else {
                document.applyEdit(document.getFullRange(), contentChangeEvent.getText());
            }
        }

        if (!onlyReloadOnSave) {
            // TODO: A consequence of this is that any existing validation events are cleared, which
            //  is kinda annoying.
            // Report any parse/shape/trait loading errors
            triggerUpdate(uri);
        }
    }

    @Override
    public void didOpen(DidOpenTextDocumentParams params) {
        LOGGER.info("DidOpen");

        String uri = params.getTextDocument().getUri();

        lifecycleManager.cancelTask(uri);

        String text = params.getTextDocument().getText();
        Project project = projects.getProject(uri);
        Document document = project.getDocument(uri);
        if (document != null) {
            document.applyEdit(null, text);
        } else {
            projects.createDetachedProject(uri, text);
        }
        // TODO: Do we need to handle canceling this?
        sendFileDiagnostics(uri);
    }

    @Override
    public void didClose(DidCloseTextDocumentParams params) {
        LOGGER.info("DidClose");

        String uri = params.getTextDocument().getUri();

        if (projects.isDetached(uri)) {
            // Only cancel tasks for detached projects, since we're dropping the project
            lifecycleManager.cancelTask(uri);
            projects.removeDetachedProject(uri);
        }

        // TODO: Clear diagnostics? Can do this by sending an empty list
    }

    @Override
    public void didSave(DidSaveTextDocumentParams params) {
        LOGGER.info("DidSave");

        String uri = params.getTextDocument().getUri();
        lifecycleManager.cancelTask(uri);
        if (params.getText() != null) {
            Project project = projects.getProject(uri);
            Document document = project.getDocument(uri);
            if (document == null) {
                // TODO: Could also load a detached project here, but I don't know how this would
                //  actually happen in practice
                client.error("Attempted to save document not tracked by server: " + uri);
                return;
            }

            document.applyEdit(null, params.getText());
        }

        triggerUpdateAndValidate(uri);
    }

    @Override
    public CompletableFuture<Either<List<CompletionItem>, CompletionList>> completion(CompletionParams params) {
        LOGGER.info("Completion");
        Project project = projects.getProject(params.getTextDocument().getUri());
        return CompletableFutures.computeAsync((cc) -> {
            CompletionHandler handler = new CompletionHandler(project);
            return Either.forLeft(handler.handle(params, cc));
        });
    }

    @Override
    public CompletableFuture<CompletionItem> resolveCompletionItem(CompletionItem unresolved) {
        LOGGER.info("ResolveCompletion");
        // TODO: Use this to add the import when a completion item is selected, if its expensive
        return completedFuture(unresolved);
    }

    @Override
    public CompletableFuture<List<Either<SymbolInformation, DocumentSymbol>>>
    documentSymbol(DocumentSymbolParams params) {
        LOGGER.info("DocumentSymbol");
        String uri = params.getTextDocument().getUri();
        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);

        return CompletableFutures.computeAsync((cc) -> {
            if (smithyFile == null) {
                return Collections.emptyList();
            }

            Collection<DocumentShape> documentShapes = smithyFile.getDocumentShapes();
            if (documentShapes.isEmpty()) {
                return Collections.emptyList();
            }

            if (cc.isCanceled()) {
                return Collections.emptyList();
            }

            List<Either<SymbolInformation, DocumentSymbol>> documentSymbols = new ArrayList<>(documentShapes.size());
            for (DocumentShape documentShape : documentShapes) {
                if (cc.isCanceled()) {
                    client.info("canceled document symbols");
                    return Collections.emptyList();
                }
                SymbolKind symbolKind;
                switch (documentShape.kind()) {
                    case Inline:
                        // No shape name in the document text, so no symbol
                        continue;
                    case DefinedMember:
                    case Elided:
                        symbolKind = SymbolKind.Property;
                        break;
                    case DefinedShape:
                    case Targeted:
                    default:
                        symbolKind = SymbolKind.Class;
                        break;
                }
                String symbolName = documentShape.shapeName().toString();
                Range symbolRange = documentShape.range();
                DocumentSymbol symbol = new DocumentSymbol(symbolName, symbolKind, symbolRange, symbolRange);
                documentSymbols.add(Either.forRight(symbol));
            }

            return documentSymbols;
        });
    }

    @Override
    public CompletableFuture<Either<List<? extends Location>, List<? extends LocationLink>>>
    definition(DefinitionParams params) {
        LOGGER.info("Definition");
        Project project = projects.getProject(params.getTextDocument().getUri());
        List<Location> locations = new DefinitionHandler(project).handle(params);
        return completedFuture(Either.forLeft(locations));
    }

    @Override
    public CompletableFuture<Hover> hover(HoverParams params) {
        LOGGER.info("Hover");
        Project project = projects.getProject(params.getTextDocument().getUri());
        // TODO: Abstract away passing minimum severity
        Hover hover = new HoverHandler(project).handle(params, minimumSeverity);
        return completedFuture(hover);
    }

    @Override
    public CompletableFuture<List<Either<Command, CodeAction>>> codeAction(CodeActionParams params) {
        List<Either<Command, CodeAction>> versionCodeActions =
                SmithyCodeActions.versionCodeActions(params).stream()
                        .map(Either::<Command, CodeAction>forRight)
                        .collect(Collectors.toList());
        return completedFuture(versionCodeActions);
    }

    @Override
    public CompletableFuture<List<? extends TextEdit>> formatting(DocumentFormattingParams params) {
        LOGGER.info("Formatting");
        String uri = params.getTextDocument().getUri();
        Project project = projects.getProject(uri);
        Document document = project.getDocument(uri);
        if (document == null) {
            return completedFuture(Collections.emptyList());
        }

        smithyfmt.Result result = smithyfmt.Formatter.format(document.borrowText().toString());
        if (result.isSuccess()) {
            final String formatted = result.getValue();

            Range range = document.getFullRange();
            TextEdit edit = new TextEdit(range, formatted);
            return completedFuture(Collections.singletonList(edit));
        } else {
            throw new RuntimeException("Failed to format document: " + result.getError());
        }
    }

    private void triggerUpdate(String uri) {
        Project project = projects.getProject(uri);
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> project.updateModelWithoutValidating(uri))
                .thenComposeAsync(unused -> sendFileDiagnostics(uri));
        lifecycleManager.putTask(uri, future);
    }

    private void triggerUpdateAndValidate(String uri) {
        Project project = projects.getProject(uri);
        CompletableFuture<Void> future = CompletableFuture
                .runAsync(() -> project.updateAndValidateModel(uri))
                .thenCompose(unused -> sendFileDiagnostics(uri));
        lifecycleManager.putTask(uri, future);
    }

    private CompletableFuture<Void> sendFileDiagnostics(String uri) {
        return CompletableFuture.runAsync(() -> {
            List<Diagnostic> diagnostics = getFileDiagnostics(uri);
            PublishDiagnosticsParams publishDiagnosticsParams = new PublishDiagnosticsParams(uri, diagnostics);
            client.publishDiagnostics(publishDiagnosticsParams);
        });
    }

    List<Diagnostic> getFileDiagnostics(String uri) {
        if (UriAdapter.isJarFile(uri) || UriAdapter.isSmithyJarFile(uri)) {
            // Don't send diagnostics to jar files since they can't be edited
            // and diagnostics could be misleading.
            return Collections.emptyList();
        }

        Project project = projects.getProject(uri);
        SmithyFile smithyFile = project.getSmithyFile(uri);
        String path = UriAdapter.toPath(uri);

        List<Diagnostic> diagnostics = project.getModelResult().getValidationEvents().stream()
                .filter(validationEvent -> validationEvent.getSeverity().compareTo(minimumSeverity) >= 0)
                .filter(validationEvent -> !UriAdapter.isJarFile(validationEvent.getSourceLocation().getFilename()))
                .filter(validationEvent -> validationEvent.getSourceLocation().getFilename().equals(path))
                .map(validationEvent -> toDiagnostic(validationEvent, smithyFile))
                .collect(Collectors.toCollection(ArrayList::new));

        if (smithyFile != null && VersionDiagnostics.hasVersionDiagnostic(smithyFile)) {
            diagnostics.add(VersionDiagnostics.forSmithyFile(smithyFile));
        }

        if (smithyFile != null && projects.isDetached(uri)) {
            diagnostics.add(DetachedDiagnostics.forSmithyFile(smithyFile));
        }

        return diagnostics;
    }

    private static Diagnostic toDiagnostic(ValidationEvent validationEvent, SmithyFile smithyFile) {
        DiagnosticSeverity severity = toDiagnosticSeverity(validationEvent.getSeverity());
        SourceLocation sourceLocation = validationEvent.getSourceLocation();

        // TODO: Improve location of diagnostics
        Range range = RangeAdapter.lineOffset(PositionAdapter.fromSourceLocation(sourceLocation));
        if (validationEvent.getShapeId().isPresent() && smithyFile != null) {
            // Event is (probably) on a member target
            if (validationEvent.containsId("Target")) {
                DocumentShape documentShape = smithyFile.getDocumentShapesByStartPosition()
                        .get(PositionAdapter.fromSourceLocation(sourceLocation));
                boolean hasMemberTarget = documentShape != null
                        && documentShape.isKind(DocumentShape.Kind.DefinedMember)
                        && documentShape.targetReference() != null;
                if (hasMemberTarget) {
                    range = documentShape.targetReference().range();
                }
            }  else {
                // Check if the event location is on a trait application
                Range traitRange = DocumentParser.forDocument(smithyFile.getDocument()).traitIdRange(sourceLocation);
                if (traitRange != null) {
                    range = traitRange;
                }
            }
        }

        String message = validationEvent.getId() + ": " + validationEvent.getMessage();
        return new Diagnostic(range, message, severity, "Smithy");
    }

    private static DiagnosticSeverity toDiagnosticSeverity(Severity severity) {
        switch (severity) {
            case ERROR:
            case DANGER:
                return  DiagnosticSeverity.Error;
            case WARNING:
                return DiagnosticSeverity.Warning;
            case NOTE:
                return DiagnosticSeverity.Information;
            default:
                return DiagnosticSeverity.Hint;
        }
    }
}
