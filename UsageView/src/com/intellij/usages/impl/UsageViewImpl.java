package com.intellij.usages.impl;

import com.intellij.ide.*;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.command.CommandProcessor;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.Factory;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.vfs.ReadonlyStatusHandler;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.Disposable;
import com.intellij.pom.Navigatable;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.ui.PopupHandler;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.SmartExpander;
import com.intellij.ui.content.Content;
import com.intellij.usages.*;
import com.intellij.usages.rules.*;
import com.intellij.util.Alarm;
import com.intellij.util.EditSourceOnDoubleClickHandler;
import com.intellij.util.Processor;
import com.intellij.util.containers.HashMap;
import com.intellij.util.containers.HashSet;
import com.intellij.util.ui.Tree;
import com.intellij.util.ui.tree.TreeUtil;

import javax.swing.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.*;
import java.util.List;
/**
 * Created by IntelliJ IDEA.
 * User: max
 * Date: Dec 16, 2004
 * Time: 4:54:09 PM
 * To change this template use File | Settings | File Templates.
 */
public class UsageViewImpl implements UsageView, UsageModelTracker.UsageModelTrackerListener {
  private UsageNodeTreeBuilder myBuilder;
  private MyPanel myRootPanel;
  private JTree myTree = new Tree();
  private Content myContent;

  private UsageViewPresentation myPresentation;
  private UsageTarget[] myTargets;
  private Factory<UsageSearcher> myUsageSearcherFactory;
  private Project myProject;

  private TreeExpander myTreeExpander;
  private boolean mySearchInProgress = true;
  private ExporterToTextFile myTextFileExporter;
  private Alarm myUpdateAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);

  private Alarm myFlushAlarm = new Alarm(Alarm.ThreadToUse.SWING_THREAD);
  private UsageModelTracker myModelTracker;
  Set<Usage> myUsages = new HashSet<Usage>();
  private Map<Usage, UsageNode> myUsageNodes = new HashMap<Usage, UsageNode>();
  private ButtonPanel myButtonPanel = new ButtonPanel();

  private boolean myChangesDetected = false;
  private List<Usage> myUsagesToFlush = new ArrayList<Usage>();
  private Factory<ProgressIndicator> myIndicatorFactory;
  private List<Disposable> myDisposables = new ArrayList<Disposable>();

  public UsageViewImpl(UsageViewPresentation presentation,
                       UsageTarget[] targets,
                       Factory<UsageSearcher> usageSearcherFactory,
                       Project project) {

    myPresentation = presentation;
    myTargets = targets;
    myUsageSearcherFactory = usageSearcherFactory;
    myProject = project;
    myRootPanel = new MyPanel(myTree);

    UsageViewTreeModelBuilder model = new UsageViewTreeModelBuilder(myPresentation, targets);
    myBuilder = new UsageNodeTreeBuilder(getActiveGroupingRules(project), getActiveFilteringRules(project), (GroupNode)model.getRoot());
    myTree.setModel(model);

    myRootPanel.setLayout(new BorderLayout());

    JPanel centralPanel = new JPanel();
    centralPanel.setLayout(new BorderLayout());
    myRootPanel.add(centralPanel, BorderLayout.CENTER);

    JPanel toolbarPanel = new JPanel(new BorderLayout());
    toolbarPanel.add(createToolbar(), BorderLayout.WEST);
    toolbarPanel.add(createFiltersToolbar(), BorderLayout.CENTER);
    myRootPanel.add(toolbarPanel, BorderLayout.WEST);

    centralPanel.add(ScrollPaneFactory.createScrollPane(myTree), BorderLayout.CENTER);
    centralPanel.add(myButtonPanel, BorderLayout.SOUTH);

    initTree();

    myTree.setCellRenderer(new UsageViewTreeCellRenderer(this));
    collapseAll();

    myModelTracker = new UsageModelTracker(project);
    myModelTracker.addListener(this);

    if (myPresentation.isShowCancelButton()) {
      addButtonToLowerPane(new Runnable() {
        public void run() {
          close();
        }
      }, "Cancel", 'C');
    }
  }

  private static UsageFilteringRule[] getActiveFilteringRules(final Project project) {
    final UsageFilteringRuleProvider[] providers = ApplicationManager.getApplication().getComponents(UsageFilteringRuleProvider.class);
    List<UsageFilteringRule> list = new ArrayList<UsageFilteringRule>();
    for (int i = 0; i < providers.length; i++) {
      list.addAll(Arrays.asList(providers[i].getActiveRules(project)));
    }
    return list.toArray(new UsageFilteringRule[list.size()]);
  }

  private static UsageGroupingRule[] getActiveGroupingRules(final Project project) {
    final UsageGroupingRuleProvider[] providers = ApplicationManager.getApplication().getComponents(UsageGroupingRuleProvider.class);
    List<UsageGroupingRule> list = new ArrayList<UsageGroupingRule>();
    for (int i = 0; i < providers.length; i++) {
      list.addAll(Arrays.asList(providers[i].getActiveRules(project)));
    }
    return list.toArray(new UsageGroupingRule[list.size()]);
  }

  public void modelChanged(boolean isPropertyChange) {
    if (!isPropertyChange) {
      myChangesDetected = true;
    }
    updateLater();
  }

  private void initTree() {
    myTree.setRootVisible(false);
    myTree.setShowsRootHandles(true);
    SmartExpander.installOn(myTree);
    TreeUtil.installActions(myTree);
    EditSourceOnDoubleClickHandler.install(myTree);
    myTree.addKeyListener(
        new KeyAdapter() {
        public void keyPressed(KeyEvent e) {
          if (KeyEvent.VK_ENTER == e.getKeyCode()) {
            TreePath leadSelectionPath = myTree.getLeadSelectionPath();
            if (leadSelectionPath == null) return;

            DefaultMutableTreeNode node = (DefaultMutableTreeNode)leadSelectionPath.getLastPathComponent();
            if (node instanceof UsageNode) {
              final Usage usage = ((UsageNode)node).getUsage();
              usage.navigate(false);
              usage.highlightInEditor();
            }
            else if (node.isLeaf()) {
              Navigatable navigatable = getNavigatableForNode(node);
              if (navigatable != null && navigatable.canNavigate()) {
                navigatable.navigate(false);
              }
            }
          }
        }
      }
    );

    PopupHandler.installPopupHandler(myTree, IdeActions.GROUP_USAGE_VIEW_POPUP, ActionPlaces.USAGE_VIEW_POPUP);
    //TODO: install speed search. Not in openapi though. It makes sense to create a common TreeEnchancer service.
  }

  private JComponent createToolbar() {
    DefaultActionGroup group = new DefaultActionGroup() {
      public void update(AnActionEvent e) {
        super.update(e);
        myButtonPanel.update(e);
      }
    };

    AnAction[] actions = createActions();
    for (int i = 0; i < actions.length; i++) {
      final AnAction action = actions[i];
      if (action != null) {
        group.add(action);
      }
    }
    ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.USAGE_VIEW_TOOLBAR,
                                                                                  group, false);
    return actionToolbar.getComponent();
  }

  private JComponent createFiltersToolbar() {
    final DefaultActionGroup group = new DefaultActionGroup();

    final AnAction[] groupingActions = createGroupingActions();
    for (int i = 0; i < groupingActions.length; i++) {
      group.add(groupingActions[i]);
    }

    final JComponent component = getComponent();
    final MergeDupLines mergeDupLines = new MergeDupLines();
    mergeDupLines.registerCustomShortcutSet(mergeDupLines.getShortcutSet(), component);
    scheduleDisposeOnClose(new Disposable() {
      public void dispose() {
        mergeDupLines.unregisterCustomShortcutSet(component);
      }
    });
    group.add(mergeDupLines);


    final AnAction[] filteringActions = createFilteringActions();
    for (AnAction filteringAction : filteringActions) {
      group.add(filteringAction);
    }

    ActionToolbar actionToolbar = ActionManager.getInstance().createActionToolbar(ActionPlaces.USAGE_VIEW_TOOLBAR, group, false);
    return actionToolbar.getComponent();
  }

  public void scheduleDisposeOnClose(final Disposable disposable) {
    myDisposables.add(disposable);
  }

  private AnAction[] createActions() {
    myTreeExpander = new TreeExpander() {
      public void expandAll() {
        UsageViewImpl.this.expandAll();
      }

      public boolean canExpand() {
        return true;
      }

      public void collapseAll() {
        UsageViewImpl.this.collapseAll();
      }

      public boolean canCollapse() {
        return true;
      }
    };

    CommonActionsManager actionsManager = CommonActionsManager.getInstance();

    myTextFileExporter = new ExporterToTextFile(this);

    final JComponent component = getComponent();

    final AnAction collapseAllAction = actionsManager.createCollapseAllAction(myTreeExpander);
    collapseAllAction.registerCustomShortcutSet(collapseAllAction.getShortcutSet(), component);

    final AnAction expandAllAction = actionsManager.createExpandAllAction(myTreeExpander);
    expandAllAction.registerCustomShortcutSet(expandAllAction.getShortcutSet(), component);

    scheduleDisposeOnClose(new Disposable() {
      public void dispose() {
        collapseAllAction.unregisterCustomShortcutSet(component);
        expandAllAction.unregisterCustomShortcutSet(component);
      }
    });

    return new AnAction[]{
      canPerformReRun() ? new ReRunAction() : null,
      new CloseAction(),
      collapseAllAction,
      expandAllAction,
      actionsManager.createPrevOccurenceAction(myRootPanel),
      actionsManager.createNextOccurenceAction(myRootPanel),
      actionsManager.installAutoscrollToSourceHandler(myProject, myTree, new MyAutoScrollToSourceOptionProvider()),
      actionsManager.createExportToTextFileAction(myTextFileExporter),
      actionsManager.createHelpAction(null)
    };
  }

  private AnAction[] createGroupingActions() {
    final UsageGroupingRuleProvider[] providers = ApplicationManager.getApplication().getComponents(UsageGroupingRuleProvider.class);
    List<AnAction> list = new ArrayList<AnAction>();
    for (UsageGroupingRuleProvider provider : providers) {
      list.addAll(Arrays.asList(provider.createGroupingActions(this)));
    }
    return list.toArray(new AnAction[list.size()]);
  }

  private AnAction[] createFilteringActions() {
    final UsageFilteringRuleProvider[] providers = ApplicationManager.getApplication().getComponents(UsageFilteringRuleProvider.class);
    List<AnAction> list = new ArrayList<AnAction>();
    for (int i = 0; i < providers.length; i++) {
      list.addAll(Arrays.asList(providers[i].createFilteringActions(this)));
    }
    return list.toArray(new AnAction[list.size()]);
  }

  public void rulesChanged() {
    final ArrayList<UsageState> states = new ArrayList<UsageState>();
    captureUsagesExpandState(new TreePath(myTree.getModel().getRoot()), states);
    final Collection<Usage> allUsages = myUsageNodes.keySet();
    reset();
    myBuilder.setGroupingRules(getActiveGroupingRules(myProject));
    myBuilder.setFilteringRules(getActiveFilteringRules(myProject));
    ApplicationManager.getApplication().runReadAction(new Runnable() {
      public void run() {
        for (Iterator<Usage> i = allUsages.iterator(); i.hasNext();) {
          Usage usage = i.next();
          if (!usage.isValid()) {
            i.remove();
            continue;
          }
          if (usage instanceof MergeableUsage) {
            ((MergeableUsage)usage).reset();
          }
          appendUsage(usage);
        }
      }
    });
    restoreUsageExpandState(states);
  }

  private void captureUsagesExpandState(TreePath pathFrom, final Collection<UsageState> states) {
    if (!myTree.isExpanded(pathFrom)) {
      return;
    }
    final DefaultMutableTreeNode node = (DefaultMutableTreeNode)pathFrom.getLastPathComponent();
    final int childCount = node.getChildCount();
    for (int idx = 0; idx < childCount; idx++) {
      final TreeNode child = node.getChildAt(idx);
      if (child instanceof UsageNode) {
        final Usage usage = ((UsageNode)child).getUsage();
        if (usage != null) {
          states.add(new UsageState(usage, myTree.getSelectionModel().isPathSelected(pathFrom.pathByAddingChild(child))));
        }
      }
      else {
        captureUsagesExpandState(pathFrom.pathByAddingChild(child), states);
      }
    }
  }

  private void restoreUsageExpandState(final Collection<UsageState> states) {
    //always expand the last level group
    final DefaultMutableTreeNode root = (DefaultMutableTreeNode)myTree.getModel().getRoot();
    for (int i = root.getChildCount() - 1; i >= 0; i--) {
      final DefaultMutableTreeNode child = (DefaultMutableTreeNode)root.getChildAt(i);
      if (child instanceof GroupNode){
        final TreePath treePath = new TreePath(child.getPath());
        myTree.expandPath(treePath);
      }
    }
    myTree.getSelectionModel().clearSelection();
    for (Iterator<UsageState> it = states.iterator(); it.hasNext();) {
      final UsageState usageState = it.next();
      usageState.restore();
    }
  }

  private void expandAll() {
    TreeUtil.expandAll(myTree);
  }

  private void collapseAll() {
    TreeUtil.collapseAll(myTree, 3);
    TreeUtil.expand(myTree, 2);
  }

  public DefaultMutableTreeNode getModelRoot() {
    return (DefaultMutableTreeNode)myTree.getModel().getRoot();
  }


  private class CloseAction extends AnAction {
    private CloseAction() {
      super("Close", null, IconLoader.getIcon("/actions/cancel.png"));
    }

    public void update(AnActionEvent e) {
      super.update(e);
      e.getPresentation().setVisible(myContent != null);
    }

    public void actionPerformed(AnActionEvent e) {
      close();
    }
  }

  private class MergeDupLines extends RuleAction {
    public MergeDupLines() {
      super(UsageViewImpl.this, "Merge usages from the same line", IconLoader.getIcon("/toolbar/filterdups.png"));
      setShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK)));
    }

    protected boolean getOptionValue() {
      return UsageViewSettings.getInstance().IS_FILTER_DUPLICATED_LINE;
    }

    protected void setOptionValue(boolean value) {
      UsageViewSettings.getInstance().IS_FILTER_DUPLICATED_LINE = value;
    }
  }

  private class ReRunAction extends AnAction {
    public ReRunAction() {
      super("Rerun", "Rerun search", IconLoader.getIcon("/actions/refreshUsages.png"));
      registerCustomShortcutSet(CommonShortcuts.getRerun(), myRootPanel);
    }

    public void actionPerformed(AnActionEvent e) {
      refreshUsages();
    }

    public void update(AnActionEvent e) {
      super.update(e);
      final Presentation presentation = e.getPresentation();
      presentation.setEnabled(allTargetsAreValid());
    }
  }

  private void refreshUsages() {
    reset();
    doReRun();
  }

  private void doReRun() {
    final Application application = ApplicationManager.getApplication();

    final Runnable process = new Runnable() {
      public void run() {
        setSearchInProgress(true);

        myChangesDetected = false;
        UsageSearcher usageSearcher = myUsageSearcherFactory.create();
        usageSearcher.generate(new Processor<Usage>() {
          public boolean process(final Usage usage) {
            appendUsageLater(usage);
            ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
            return indicator != null ? !indicator.isCanceled() : true;
          }
        });

        setSearchInProgress(false);
      }
    };

    if (myIndicatorFactory!=null) {
      UsageViewImplUtil.runProcessWithProgress(myIndicatorFactory.create(), process, new Runnable() {
        public void run() {}
      });
    }  else {
      application.runProcessWithProgressSynchronously(
        process, UsageViewManagerImpl.getProgressTitle(myPresentation), true, myProject
      );
    }
  }

  private void reset() {
    myUsageNodes = new HashMap<Usage, UsageNode>();
    myIsFirstVisibleUsageFound = false;
    ((UsageViewTreeModelBuilder)myTree.getModel()).reset();
    TreeUtil.expand(myTree, 2);
  }

  public void appendUsageLater(final Usage usage) {
    myUsages.add(usage);
    ProgressIndicator indicator = ProgressManager.getInstance().getProgressIndicator();
    if (indicator != null) {
      myFlushAlarm.cancelAllRequests();
      myFlushAlarm.addRequest(new Runnable() {
        public void run() {
          flush();
        }
      }, 300, indicator.getModalityState());
    }

    synchronized (myUsagesToFlush) {
      myUsagesToFlush.add(usage);
      if (myUsagesToFlush.size() > 50) {
        SwingUtilities.invokeLater(new Runnable() {
          public void run() {
            flush();
          }
        });
      }
    }
  }

  private void flush() {
    synchronized (myUsagesToFlush) {
      ApplicationManager.getApplication().runReadAction(new Runnable() {
        public void run() {
          for (int i = 0; i < myUsagesToFlush.size(); i++) {
            final Usage usage = myUsagesToFlush.get(i);
            appendUsage(usage);
          }
        }
      });
      myUsagesToFlush.clear();
    }
  }

  private boolean myIsFirstVisibleUsageFound = false;

  public void appendUsage(Usage usage) {
    // invoke in ReadAction to be be sure that usages are not invalidated while the tree is being built
    ApplicationManager.getApplication().assertReadAccessAllowed();
    if (!usage.isValid()) {
      // because the view is built incrementally with Alarm, the usage may be already invalid, so need filter such cases
      return;
    }
    myUsages.add(usage);
    final UsageNode node = myBuilder.appendUsage(usage);
    myUsageNodes.put(usage, node);
    if (!myIsFirstVisibleUsageFound && node != null) { //first visible usage found;
      showNode(node);
      myIsFirstVisibleUsageFound = true;
    }
  }

  public void removeUsage(Usage usage) {
    final UsageNode node = myUsageNodes.get(usage);

    if (node != null) {
      ((DefaultTreeModel)myTree.getModel()).removeNodeFromParent (node);
      myUsageNodes.remove(usage);
      ((GroupNode)myTree.getModel().getRoot()).removeUsage(node);
    }
  }

  public void includeUsages(Usage[] usages) {
    for (int i = 0; i < usages.length; i++) {
      final UsageNode node = myUsageNodes.get(usages[i]);
      if (node != null) {
        node.setUsageExcluded(false);
      }
    }
    updateImmediately();
  }

  public void excludeUsages(Usage[] usages) {
    for (int i = 0; i < usages.length; i++) {
      final UsageNode node = myUsageNodes.get(usages[i]);
      if (node != null) {
        node.setUsageExcluded(true);
      }
    }
    updateImmediately();
  }

  public void selectUsages(Usage[] usages) {
    if (usages == null) return;

    List<TreePath> pathes = new LinkedList<TreePath>();

    for (int i = 0; i < usages.length; i++) {
      final UsageNode node = myUsageNodes.get(usages[i]);

      if (node != null) {
        pathes.add(new TreePath(node.getPath()));
      }
    }

    myTree.setSelectionPaths(pathes.toArray(new TreePath[pathes.size()]));
    if (pathes.size() > 0) myTree.scrollPathToVisible(pathes.get(0));
  }

  public JComponent getComponent() {
    return myRootPanel;
  }

  public int getUsagesCount() {
    return myUsageNodes.size();
  }

  public void setContent(Content content) {
    myContent = content;
    content.setDisposer(this);
  }

  private void updateImmediately() {
    if (myProject.isDisposed()) return;
    checkNodeValidity((DefaultMutableTreeNode)myTree.getModel().getRoot());
  }

  private void checkNodeValidity(DefaultMutableTreeNode node) {
    Enumeration enumeration = node.children();
    while (enumeration.hasMoreElements()) {
      checkNodeValidity((DefaultMutableTreeNode)enumeration.nextElement());
    }
    if (node instanceof Node && node != getModelRoot()) ((Node)node).update();
  }

  private void updateLater() {
    myUpdateAlarm.cancelAllRequests();
    myUpdateAlarm.addRequest(
        new Runnable() {
        public void run() {
          updateImmediately();
        }
      },
      300
    );
  }

  public void close() {
    com.intellij.usageView.UsageViewManager.getInstance(myProject).closeContent(myContent);
  }

  public void dispose() {
    for (Disposable disposable : myDisposables) {
      disposable.dispose();
    }
    myDisposables.clear();
    myModelTracker.removeListener(this);
    myModelTracker.dispose();
    myUpdateAlarm.cancelAllRequests();
  }

  public boolean isSearchInProgress() {
    return mySearchInProgress;
  }

  public void setSearchInProgress(boolean searchInProgress) {
    mySearchInProgress = searchInProgress;
    SwingUtilities.invokeLater(new Runnable() {
      public void run() {
        flush();
        final UsageNode firstUsageNode = ((UsageViewTreeModelBuilder)myTree.getModel()).getFirstUsageNode();
        if (firstUsageNode != null) { //first usage;
          showNode(firstUsageNode);
        }
      }
    });

  }

  private void showNode(final UsageNode node) {
    TreePath usagePath = new TreePath(node.getPath());
    myTree.expandPath(usagePath.getParentPath());
    myTree.setSelectionPath(usagePath);
  }

  public void addButtonToLowerPane(final Runnable runnable, String text, char mnemonic) {
    int index = myButtonPanel.getComponentCount();

    if (index > 0 && myPresentation.isShowCancelButton()) index--;

    myButtonPanel.add(
      index,
      runnable,
      text,
      mnemonic
    );
  }

  public void addPerformOperationAction(final Runnable processRunnable,
                                        final String commandName,
                                        final String cannotMakeString,
                                        String shortDescription,
                                        char mnemonic) {

    addButtonToLowerPane(
      new Runnable() {
        public void run() {
          checkReadonlyUsages();
          PsiDocumentManager.getInstance(myProject).commitAllDocuments();
          if (cannotMakeString != null && myChangesDetected) {
            if (canPerformReRun() && allTargetsAreValid()) {
              int answer = Messages.showYesNoDialog(
                myProject,
                cannotMakeString + "\nWould you like to rerun the search now?",
                "Error",
                Messages.getErrorIcon()
              );
              if (answer == 0) {
                refreshUsages();
              }
            }
            else {
              Messages.showMessageDialog(
                myProject,
                cannotMakeString,
                "Error",
                Messages.getErrorIcon()
              );
              //todo[myakovlev] request focus to tree
              //myUsageView.getTree().requestFocus();
            }
            return;
          }

          close();

          CommandProcessor.getInstance().executeCommand(
              myProject, new Runnable() {
              public void run() {
                processRunnable.run();
              }
            },
            commandName,
            null
          );

        }

      }, shortDescription, mnemonic);
  }

  private boolean allTargetsAreValid() {
    for (int i = 0; i < myTargets.length; i++) {
      UsageTarget target = myTargets[i];
      if (!target.isValid()) {
        return false;
      }
    }

    return true;
  }

  public UsageViewPresentation getPresentation() {
    return myPresentation;
  }

  private boolean canPerformReRun() {
    return myUsageSearcherFactory != null;
  }

  private void checkReadonlyUsages() {
    final Set<VirtualFile> readOnlyUsages = getReadOnlyUsagesFiles();

    if (!readOnlyUsages.isEmpty()) {
        ReadonlyStatusHandler.getInstance(myProject).ensureFilesWritable(readOnlyUsages.toArray(new VirtualFile[readOnlyUsages.size()]));      
      }
  }

  private Set<Usage> getReadOnlyUsages() {
    final Set<Usage> result = new HashSet<Usage>();
    final Set<Map.Entry<Usage,UsageNode>> usages = myUsageNodes.entrySet();
    for (Iterator<Map.Entry<Usage,UsageNode>> i = usages.iterator(); i.hasNext();) {
      Map.Entry<Usage,UsageNode> entry = i.next();
      Usage usage = entry.getKey();
      UsageNode node = entry.getValue();
      if (node != null && !node.isExcluded() && usage.isReadOnly()) {
        result.add(usage);
      }
    }
    return result;
  }

  private Set<VirtualFile> getReadOnlyUsagesFiles() {
    Set<Usage> usages = getReadOnlyUsages();
    Set<VirtualFile> result = new HashSet<VirtualFile>();
    for (Iterator<Usage> i = usages.iterator(); i.hasNext();) {
      Usage usage = i.next();
      if (usage instanceof UsageInFile) {
        UsageInFile usageInFile = (UsageInFile)usage;
        result.add(usageInFile.getFile());
      }

      if (usage instanceof UsageInFiles) {
        UsageInFiles usageInFiles = (UsageInFiles)usage;
        result.addAll(Arrays.asList(usageInFiles.getFiles()));
      }
    }
    return result;
  }

  public Set<Usage> getExcludedUsages() {
    Set<Usage> result = new HashSet<Usage>();
    Collection<UsageNode> usageNodes = myUsageNodes.values();
    for (Iterator<UsageNode> i = usageNodes.iterator(); i.hasNext();) {
      final UsageNode node = i.next();
      if (node == null) {
        continue;
      }
      if (node.isExcluded()) {
        result.add(node.getUsage());
      }
    }

    return result;
  }


  public Node getSelectedNode() {
    TreePath leadSelectionPath = myTree.getLeadSelectionPath();
    if (leadSelectionPath == null) return null;

    DefaultMutableTreeNode node = (DefaultMutableTreeNode)leadSelectionPath.getLastPathComponent();
    return node instanceof Node ? (Node)node : null;
  }

  public Node[] getSelectedNodes() {
    TreePath[] leadSelectionPath = myTree.getSelectionPaths();
    if (leadSelectionPath == null || leadSelectionPath.length == 0) return null;

    final List<Node> result = new ArrayList<Node>();
    for (int i = 0; i < leadSelectionPath.length; i++) {
      final Object lastPathComponent = leadSelectionPath[i].getLastPathComponent();
      if (lastPathComponent instanceof Node) {
        final Node node = (Node)lastPathComponent;
        result.add(node);
      }
    }
    return result.isEmpty() ? null : result.toArray(new Node[result.size()]);
  }

  public Set<Usage> getSelectedUsages() {
    TreePath[] selectionPaths = myTree.getSelectionPaths();
    if (selectionPaths == null) {
      return null;
    }

    Set<Usage> usages = new HashSet<Usage>();
    for (int i = 0; i < selectionPaths.length; i++) {
      TreePath selectionPath = selectionPaths[i];
      DefaultMutableTreeNode node = (DefaultMutableTreeNode)selectionPath.getLastPathComponent();
      collectUsages(node, usages);
    }

    return usages;
  }

  public Set<Usage> getUsages() {
    return myUsages;
  }

  private void collectUsages(DefaultMutableTreeNode node, Set<Usage> usages) {
    if (node instanceof UsageNode) {
      UsageNode usageNode = (UsageNode)node;
      final Usage usage = usageNode.getUsage();
      if (usage != null && usage.isValid()) {
        usages.add(usage);
      }
    }

    Enumeration enumeration = node.children();
    while (enumeration.hasMoreElements()) {
      DefaultMutableTreeNode child = (DefaultMutableTreeNode)enumeration.nextElement();
      collectUsages(child, usages);
    }
  }

  private UsageTarget[] getSelectedUsageTargets() {
    TreePath[] selectionPaths = myTree.getSelectionPaths();
    if (selectionPaths == null) return null;

    Set<UsageTarget> targets = new HashSet<UsageTarget>();
    for (int i = 0; i < selectionPaths.length; i++) {
      TreePath selectionPath = selectionPaths[i];
      Object lastPathComponent = selectionPath.getLastPathComponent();
      if (lastPathComponent instanceof UsageTargetNode) {
        UsageTargetNode usageTargetNode = (UsageTargetNode)lastPathComponent;
        UsageTarget target = usageTargetNode.getTarget();
        if (target != null && target.isValid()) {
          targets.add(target);
        }
      }
    }

    return targets.size() > 0 ? targets.toArray(new UsageTarget[targets.size()]) : null;
  }

  private static Navigatable getNavigatableForNode(DefaultMutableTreeNode node) {
    if (node == null) {
      return null;
    }
    Object userObject = node.getUserObject();
    if (userObject instanceof Navigatable) {
      final Navigatable navigatable = (Navigatable)userObject;
      return navigatable.canNavigate() ? navigatable : null;
    }
    return null;
  }

  /** nodes with non-valid data are not included */
  private static Navigatable[] getNavigatablesForNodes(Node[] nodes) {
    if (nodes == null) {
      return null;
    }
    final ArrayList<Navigatable> result = new ArrayList<Navigatable>();
    for (int i = 0; i < nodes.length; i++) {
      final Node node = nodes[i];
      if (!node.isDataValid()) {
        continue;
      }
      Object userObject = node.getUserObject();
      if (userObject instanceof Navigatable) {
        result.add((Navigatable)userObject);
      }
    }
    return result.toArray(new Navigatable[result.size()]);
  }

  public boolean areTargetsValid() {
    return ((UsageViewTreeModelBuilder)myTree.getModel()).areTargetsValid();
  }

  private class MyPanel extends JPanel implements DataProvider, OccurenceNavigator {
    private OccurenceNavigatorSupport mySupport;

    public MyPanel(JTree tree) {
      mySupport = new OccurenceNavigatorSupport(tree) {
        protected Navigatable createDescriptorForNode(DefaultMutableTreeNode node) {
          if (node.getChildCount() > 0) return null;
          if (!((Node)node).isValid()) return null;
          return getNavigatableForNode(node);
        }

        public String getNextOccurenceActionName() {
          return "Next Occurence";
        }

        public String getPreviousOccurenceActionName() {
          return "Previous Occurence";
        }
      };
    }

    public boolean hasNextOccurence() {
      return mySupport.hasNextOccurence();
    }

    public boolean hasPreviousOccurence() {
      return mySupport.hasPreviousOccurence();
    }

    public OccurenceInfo goNextOccurence() {
      return mySupport.goNextOccurence();
    }

    public OccurenceInfo goPreviousOccurence() {
      return mySupport.goPreviousOccurence();
    }

    public String getNextOccurenceActionName() {
      return mySupport.getNextOccurenceActionName();
    }

    public String getPreviousOccurenceActionName() {
      return mySupport.getPreviousOccurenceActionName();
    }

    public Object getData(String dataId) {
      Node node = getSelectedNode();

      if (dataId.equals(USAGE_VIEW)) {
        return UsageViewImpl.this;
      }

      if (dataId.equals(DataConstants.NAVIGATABLE_ARRAY)) {
        return getNavigatablesForNodes(getSelectedNodes());
      }

      if (dataId.equals(DataConstants.EXPORTER_TO_TEXT_FILE)) {
        return myTextFileExporter;
      }

      if (dataId.equals(USAGES)) {
        final Set<Usage> selectedUsages = getSelectedUsages();
        if (selectedUsages == null) {
          return null;
        }
        for (Iterator it = selectedUsages.iterator(); it.hasNext();) {
          final Usage usage = (Usage)it.next();
          if (!usage.isValid()) {
            it.remove();
          }
        }
        return selectedUsages.toArray(new Usage[selectedUsages.size()]);
      }

      if (dataId.equals(USAGE_TARGETS)) {
        final UsageTarget[] selectedUsageTargets = getSelectedUsageTargets();
        if (selectedUsageTargets != null) {
          return selectedUsageTargets;
        }
      }

      if (node != null) {
        Object userObject = node.getUserObject();
        if (userObject instanceof DataProvider) {
          DataProvider dataProvider = (DataProvider)userObject;
          return dataProvider.getData(dataId);
        }
      }

      return null;
    }
  }

  private static class MyAutoScrollToSourceOptionProvider implements AutoScrollToSourceOptionProvider {
    public boolean isAutoScrollMode() {
      return UsageViewSettings.getInstance().IS_AUTOSCROLL_TO_SOURCE;
    }

    public void setAutoScrollMode(boolean state) {
      UsageViewSettings.getInstance().IS_AUTOSCROLL_TO_SOURCE = state;
    }
  }

  private final class ButtonPanel extends JPanel {
    public ButtonPanel() {
      setLayout(new FlowLayout(FlowLayout.LEFT, 8, 0));
    }

    public void add(int index, final Runnable runnable, String text, char mnemonic) {
      final JButton button = new JButton(text);

      button.setFocusable(false);
      button.addActionListener(new ActionListener() {
                                     public void actionPerformed(ActionEvent e) {
                                       runnable.run();
                                     }
                                   });

      button.setMnemonic(mnemonic);

      add(button, index);

      invalidate();
      if (getParent() != null) {
        getParent().validate();
      }
    }

    void update(AnActionEvent e) {
      for (int i = 0; i < getComponentCount(); ++i) {
        Component component = getComponent(i);
        if (component instanceof JButton) {
          final JButton button = (JButton)component;
          button.setEnabled(!isSearchInProgress());
        }
      }
    }
  }

  private class UsageState {
    private final Usage myUsage;
    private final boolean mySelected;

    public UsageState(final Usage usage) {
      this(usage, false);
    }

    public UsageState(final Usage usage, boolean isSelected) {
      myUsage = usage;
      mySelected = isSelected;
    }

    public void restore() {
      final UsageNode node = myUsageNodes.get(myUsage);
      if (node == null) {
        return;
      }
      final DefaultMutableTreeNode parentGroupingNode = (DefaultMutableTreeNode)node.getParent();
      if (parentGroupingNode != null) {
        final TreePath treePath = new TreePath(parentGroupingNode.getPath());
        myTree.expandPath(treePath);
        if (mySelected) {
          myTree.addSelectionPath(treePath.pathByAddingChild(node));
        }
      }
    }
  }

  public void setProgressIndicatorFactory(final Factory<ProgressIndicator> indicatorFactory) {
    myIndicatorFactory = indicatorFactory;
  }
}
