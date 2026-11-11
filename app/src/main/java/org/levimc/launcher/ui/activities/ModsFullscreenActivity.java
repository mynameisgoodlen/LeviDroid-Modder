package org.levimc.launcher.ui.activities;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.widget.PopupMenu;
import androidx.core.widget.CompoundButtonCompat;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.core.app.ActivityOptionsCompat;
import androidx.core.widget.NestedScrollView;
import android.graphics.Canvas;
import org.levimc.launcher.R;
import org.levimc.launcher.core.mods.FileHandler;
import org.levimc.launcher.core.mods.GitHubModInstaller;
import org.levimc.launcher.core.mods.GitHubModRepository;
import org.levimc.launcher.core.mods.MediaFireModInstaller;
import org.levimc.launcher.core.mods.Mod;
import org.levimc.launcher.core.mods.ModManager;
import org.levimc.launcher.core.versions.GameVersion;
import org.levimc.launcher.core.mods.inbuilt.manager.InbuiltModManager;
import org.levimc.launcher.core.versions.VersionManager;
import org.levimc.launcher.ui.adapter.ModsAdapter;
import org.levimc.launcher.ui.dialogs.CustomAlertDialog;
import org.levimc.launcher.core.packages.engine.Cancel;
import org.levimc.launcher.ui.packages.InstalledFragment;
import org.levimc.launcher.ui.packages.PackageActions;
import org.levimc.launcher.ui.animation.DynamicAnim;
import org.levimc.launcher.ui.views.MainViewModel;
import org.levimc.launcher.ui.views.MainViewModelFactory;
import org.levimc.launcher.util.GithubReleaseUpdater;
import org.levimc.launcher.util.StorageAccess;
import org.levimc.launcher.ui.widget.SegmentedControl;
import org.levimc.launcher.ui.widget.UiKit;
import org.levimc.launcher.util.PersonalizationManager;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.io.File;

public class ModsFullscreenActivity extends BaseActivity {

    private RecyclerView modsRecycler;
    private ModsAdapter modsAdapter;
    private MainViewModel viewModel;
    private TextView totalModsCount;
    private TextView enabledModsCount;
    private TextView totalSizeCount;
    private String selectedVersionCode;
    private TextView subtitle;
    private EditText searchInput;
    private CompoundButton onlyEnabledCheck;
    private CompoundButton modMenuSwitch;
    private SegmentedControl filterTabs;
    private TextView sortNameHeader;
    private View emptyView;
    private TextView emptyText;
    private final List<Mod> allMods = new ArrayList<>();
    private Map<String, InstalledFragment.Model.Row> pkgRowsByFolder = Collections.emptyMap();
    private static final int TAB_ALL = 0;
    private static final int TAB_NORMAL = 1;
    private static final int TAB_LEVIMC = 2;
    private static final int SORT_NONE = 0;
    private static final int SORT_ASC = 1;
    private static final int SORT_DESC = 2;
    private int sortMode = SORT_NONE;
    private ActivityResultLauncher<Intent> pickModLauncher;
    private FileHandler fileHandler;
    private InbuiltModManager inbuiltModManager;
    private int lastModsCount = -1;
    private GitHubModInstaller githubModInstaller;
    private MediaFireModInstaller mediaFireModInstaller;
    private boolean githubUpdateCheckRunning;
    private boolean githubBatchUpdating;
    private int githubBatchRemaining;
    private String lastShownUpdateSignature = "";
    private final Cancel pkgCancel = new Cancel();
    private int pkgRequest;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_mods_fullscreen);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            DynamicAnim.applyPressScaleRecursively(root);
        }

        inbuiltModManager = InbuiltModManager.getInstance(this);
        setupViews();
        setupViewModel();
        setupRecyclerView();
        fileHandler = new FileHandler(this, viewModel, VersionManager.get(this));
        githubModInstaller = new GitHubModInstaller(this, fileHandler);
        mediaFireModInstaller = new MediaFireModInstaller(this, fileHandler);

        pickModLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        for (Uri uri : StorageAccess.selectedUris(result.getData())) {
                            StorageAccess.retainReadPermission(this, result.getData(), uri);
                        }
                        fileHandler.processIncomingFilesWithConfirmation(
                                result.getData(), createImportCallback(), true);
                    }
                }
        );
    }

    private void setupViews() {
        Button addModButton = findViewById(R.id.add_mod_fullscreen_button);
        addModButton.setVisibility(View.VISIBLE);
        addModButton.setOnClickListener(v -> showAddModDialog());
        DynamicAnim.applyPressScale(addModButton);

        View getModsButton = findViewById(R.id.mods_get_button);
        getModsButton.setOnClickListener(this::showGetModsMenu);

        modMenuSwitch = findViewById(R.id.mod_menu_switch);
        syncModMenuSwitch();

        subtitle = findViewById(R.id.mods_subtitle);
        emptyView = findViewById(R.id.mods_empty);
        emptyText = findViewById(R.id.mods_empty_text);

        searchInput = findViewById(R.id.mods_search);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                applyFilters();
            }
        });

        onlyEnabledCheck = findViewById(R.id.mods_only_enabled);
        onlyEnabledCheck.setOnCheckedChangeListener((b, checked) -> applyFilters());

        filterTabs = findViewById(R.id.mods_filter_tabs);
        filterTabs.setItems(getString(R.string.mods_filter_all), getString(R.string.mods_filter_normal),
                getString(R.string.mods_filter_packages));
        filterTabs.setSelectedIndex(TAB_ALL, false);
        filterTabs.setOnSegmentSelectedListener(i -> applyFilters());

        sortNameHeader = findViewById(R.id.mods_sort_name);
        sortNameHeader.setOnClickListener(v -> {
            sortMode = (sortMode + 1) % 3;
            updateSortHeader();
            applyFilters();
        });
        updateSortHeader();

        findViewById(R.id.mods_refresh_button).setOnClickListener(v -> {
            if (viewModel != null) {
                viewModel.reloadMods();
            }
        });

        // PersonalizationManager re-tints switches and buttons, not checkboxes.
        int[][] states = {{android.R.attr.state_checked}, {}};
        CompoundButtonCompat.setButtonTintList(onlyEnabledCheck, new ColorStateList(states,
                new int[]{UiKit.accent(this), UiKit.secondaryText(this)}));

        totalModsCount = findViewById(R.id.total_mods_count);
        enabledModsCount = findViewById(R.id.enabled_mods_count);
        totalSizeCount = findViewById(R.id.total_size_count);

        View root = findViewById(android.R.id.content);
        if (root != null) {
            new PersonalizationManager(this).applyAccentToView(root, this);
        }
    }

    private void syncModMenuSwitch() {
        modMenuSwitch.setOnCheckedChangeListener(null);
        modMenuSwitch.setChecked(inbuiltModManager.isModMenuEnabled());
        modMenuSwitch.setOnCheckedChangeListener((b, checked) -> {
            if (checked == inbuiltModManager.isModMenuEnabled()) return;
            inbuiltModManager.setModMenuEnabled(checked);
            Toast.makeText(this, checked ? R.string.mod_menu_enabled : R.string.mod_menu_disabled,
                    Toast.LENGTH_SHORT).show();
        });
    }

    /** "Get mods": LeviMC mods (package browser) or the external mod catalog. */
    private void showGetModsMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, R.string.pkg_title_mods);
        menu.getMenu().add(0, 2, 1, R.string.external_mods);
        menu.setOnMenuItemClickListener(item -> {
            Intent intent = item.getItemId() == 1
                    ? org.levimc.launcher.ui.packages.PackagesActivity.intent(this, VersionManager.get(this).getSelectedVersion(), null)
                    : new Intent(this, ExternalModsActivity.class);
            startActivity(intent, ActivityOptionsCompat.makeCustomAnimation(this, R.anim.fade_in, R.anim.fade_out).toBundle());
            return true;
        });
        menu.show();
    }

    private void updateSortHeader() {
        String label = getString(R.string.mods_col_name);
        if (sortMode == SORT_ASC) label += " ▲";
        else if (sortMode == SORT_DESC) label += " ▼";
        sortNameHeader.setText(label);
    }

    /** A mod folder a LeviMC (lip) package owns. */
    private boolean isLeviMcMod(Mod mod) {
        return pkgRowsByFolder.containsKey(InstalledFragment.Model.folderKey(mod.getId()));
    }

    /** Shows the mods matching the search, the "only enabled" box and the source tab, in the chosen order. */
    private void applyFilters() {
        if (modsAdapter == null || filterTabs == null) return;
        String query = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        boolean onlyEnabled = onlyEnabledCheck.isChecked();
        int tab = filterTabs.getSelectedIndex();

        List<Mod> shown = new ArrayList<>();
        for (Mod mod : allMods) {
            if (onlyEnabled && !mod.isEnabled()) continue;
            if (tab == TAB_NORMAL && isLeviMcMod(mod)) continue;
            if (tab == TAB_LEVIMC && !isLeviMcMod(mod)) continue;
            if (!query.isEmpty() && !matches(mod, query)) continue;
            shown.add(mod);
        }
        if (sortMode != SORT_NONE) {
            Comparator<Mod> byName = Comparator.comparing(m -> lower(m.getDisplayName()));
            shown.sort(sortMode == SORT_ASC ? byName : byName.reversed());
        }

        // Drag-to-reorder edits the load order, so it only works on the full, unsorted list.
        boolean filtered = !query.isEmpty() || onlyEnabled || tab != TAB_ALL || sortMode != SORT_NONE;
        modsAdapter.setReorderEnabled(!filtered);
        modsAdapter.updateMods(shown);

        boolean empty = shown.isEmpty();
        modsRecycler.setVisibility(empty ? View.GONE : View.VISIBLE);
        emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
        emptyText.setText(allMods.isEmpty() ? R.string.mods_empty : R.string.mods_empty_filtered);
    }

    private static boolean matches(Mod mod, String query) {
        return lower(mod.getDisplayName()).contains(query)
                || lower(mod.getId()).contains(query)
                || lower(mod.getAuthor()).contains(query);
    }

    private static String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private void startFilePicker() {
        pickModLauncher.launch(StorageAccess.downloadsPicker(true));
    }

    private FileHandler.FileOperationCallback createImportCallback() {
        return new FileHandler.FileOperationCallback() {
            @Override
            public void onSuccess(int processedFiles) {
                Toast.makeText(ModsFullscreenActivity.this,
                        getString(R.string.files_processed, processedFiles), Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String errorMessage) {
                Toast.makeText(ModsFullscreenActivity.this, errorMessage, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onProgressUpdate(int progress) {
            }
        };
    }

    private void setupViewModel() {
        viewModel = new ViewModelProvider(this, new MainViewModelFactory(getApplication())).get(MainViewModel.class);

        org.levimc.launcher.core.versions.GameVersion selectedVersion = VersionManager.get(this).getSelectedVersion();
        if (selectedVersion != null) {
            viewModel.setCurrentVersion(selectedVersion);
        }
        selectedVersionCode = selectedVersion != null ? selectedVersion.versionCode : null;

        viewModel.getModsLiveData().observe(this, this::updateModsUI);
    }

    private void setupRecyclerView() {
        modsRecycler = findViewById(R.id.mods_recycler_fullscreen);
        modsAdapter = new ModsAdapter(new ArrayList<>());
        modsAdapter.setCurrentVersionCode(selectedVersionCode);
        modsRecycler.setLayoutManager(new LinearLayoutManager(this));
        modsRecycler.setAdapter(modsAdapter);
        if (modsRecycler.getItemAnimator() instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) modsRecycler.getItemAnimator()).setSupportsChangeAnimations(false);
        }

        modsRecycler.post(() -> DynamicAnim.staggerRecyclerChildren(modsRecycler));

        modsAdapter.setOnModClickListener((mod, position, sharedView) -> {
            Intent intent = new Intent(this, ModDetailActivity.class);
            intent.putExtra("mod_filename", mod.getId());
            intent.putExtra("mod_position", position);
            startActivity(intent);
        });

        modsAdapter.setOnPkgUpdateClickListener((mod, row) -> {
            GameVersion version = currentVersion();
            if (version != null) {
                PackageActions.update(this, version, row, pkgDone(null));
            }
        });

        modsAdapter.setOnModEnableChangeListener((mod, enabled) -> {
            if (viewModel != null) {
                viewModel.setModEnabled(mod.getId(), enabled);
                updateModsCount(); 
            }
        });
        
        modsAdapter.setOnModReorderListener(reorderedMods -> {
            if (viewModel != null) {
                viewModel.reorderMods(reorderedMods);
                Toast.makeText(this, R.string.mod_reordered, Toast.LENGTH_SHORT).show();
            }
        });
        
        NestedScrollView nestedScrollView = findViewById(R.id.nested_scroll_view);
        ItemTouchHelper.SimpleCallback simpleCallback = new ItemTouchHelper.SimpleCallback(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT) {
            private long lastScrollTime = 0;

            @Override
            public int getDragDirs(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                return modsAdapter.isReorderEnabled() ? super.getDragDirs(recyclerView, viewHolder) : 0;
            }

            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                int fromPosition = viewHolder.getAdapterPosition();
                int toPosition = target.getAdapterPosition();
                modsAdapter.moveItem(fromPosition, toPosition);
                return true;
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                modsAdapter.setDragHandleVisible(viewHolder, false);
                if (modsAdapter.isReorderEnabled()) {
                    modsAdapter.commitReorder();
                }
            }

            @Override
            public void onSelectedChanged(@Nullable RecyclerView.ViewHolder viewHolder, int actionState) {
                super.onSelectedChanged(viewHolder, actionState);
                if (viewHolder != null && actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    modsAdapter.setDragHandleVisible(viewHolder, true);
                }
            }

            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG && isCurrentlyActive && nestedScrollView != null) {
                    long now = System.currentTimeMillis();
                    if (now - lastScrollTime > 16) {
                        int[] location = new int[2];
                        viewHolder.itemView.getLocationOnScreen(location);
                        int y = location[1];

                        nestedScrollView.getLocationOnScreen(location);
                        int svY = location[1];
                        int svHeight = nestedScrollView.getHeight();

                        int scrollZone = (int) (80 * recyclerView.getResources().getDisplayMetrics().density);
                        int scrollAmount = 0;
                        int maxScrollSpeed = 15;

                        if (y < svY + scrollZone) {
                            float ratio = 1.0f - Math.max(0, y - svY) / (float) scrollZone;
                            scrollAmount = (int) (-maxScrollSpeed * ratio);
                        } else if (y + viewHolder.itemView.getHeight() > svY + svHeight - scrollZone) {
                            float ratio = 1.0f - Math.max(0, svY + svHeight - (y + viewHolder.itemView.getHeight())) / (float) scrollZone;
                            scrollAmount = (int) (maxScrollSpeed * ratio);
                        }

                        if (scrollAmount != 0) {
                            nestedScrollView.scrollBy(0, scrollAmount);
                            lastScrollTime = now;
                            recyclerView.invalidate();
                        }
                    }
                }
            }

            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int pos = viewHolder.getAdapterPosition();
                Mod mod = modsAdapter.getItem(pos);
                GameVersion version = currentVersion();
                if (version == null) {
                    modsAdapter.notifyItemChanged(pos);
                    return;
                }
                // A folder a package owns is removed by uninstalling the
                // package (or refused for a dependency): PackageActions asks.
                PackageActions.deleteModFolder(ModsFullscreenActivity.this, version, mod, pkgDone(mod));
            }
        };
        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleCallback);
        itemTouchHelper.attachToRecyclerView(modsRecycler);
        modsAdapter.setItemTouchHelper(itemTouchHelper);
    }

    /** The instance whose mods are listed (the one the mod manager is bound to). */
    private GameVersion currentVersion() {
        GameVersion v = ModManager.getInstance().getCurrentVersion();
        return v != null ? v : VersionManager.get(this).getSelectedVersion();
    }

    /**
     * After a package action: puts a swiped row back (found by its folder: the
     * list may have changed while the action asked and ran; the reload
     * drops it when it is gone) and reloads the mods. Holds the activity
     * weakly, since the action's task may outlive it.
     */
    private PackageActions.Done pkgDone(Mod swiped) {
        WeakReference<ModsFullscreenActivity> self = new WeakReference<>(this);
        String swipedId = swiped == null ? null : swiped.getId();
        return changed -> {
            ModsFullscreenActivity a = self.get();
            if (a == null || a.isFinishing() || a.isDestroyed()) {
                return;
            }
            if (swipedId != null && a.modsAdapter != null) {
                for (int i = 0; i < a.modsAdapter.getItemCount(); i++) {
                    if (swipedId.equals(a.modsAdapter.getItem(i).getId())) {
                        a.modsAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            }
            if (changed && a.viewModel != null) {
                a.viewModel.reloadMods();
            }
        };
    }

    /** Marks the folders packages own (badge, "Update to X") once their packages are read. */
    private void loadPkgRows(List<Mod> mods) {
        int request = ++pkgRequest;
        InstalledFragment.Loader.loadForMods(pkgCancel, currentVersion(), mods, rows -> {
            if (request == pkgRequest && modsAdapter != null) {
                pkgRowsByFolder = InstalledFragment.Model.byFolder(rows);
                modsAdapter.setPkgRows(pkgRowsByFolder);
                if (filterTabs != null && filterTabs.getSelectedIndex() != TAB_ALL) {
                    applyFilters();
                }
            }
        });
    }

    @Override
    protected void onDestroy() {
        pkgCancel.cancel();
        super.onDestroy();
    }

    private void updateModsUI(List<Mod> mods) {
        if (modsAdapter != null) {
            allMods.clear();
            if (mods != null) allMods.addAll(mods);
            applyFilters();
            loadPkgRows(mods);
            updateModsCount();
            checkGitHubUpdates(mods);
            if (modsRecycler != null) {
                int count = (mods != null) ? mods.size() : 0;
                if (lastModsCount == -1 || count != lastModsCount) {
                    modsRecycler.post(() -> DynamicAnim.staggerRecyclerChildren(modsRecycler));
                }
                lastModsCount = count;
            }
        }
    }

    private void updateModsCount() {
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        
        int total = (mods != null ? mods.size() : 0);
        int enabled = 0;
        
        if (mods != null) {
            for (Mod mod : mods) {
                if (mod.isEnabled()) {
                    enabled++;
                }
            }
        }

        if (totalModsCount != null) {
            totalModsCount.setText(String.valueOf(total));
        }
        if (enabledModsCount != null) {
            enabledModsCount.setText(String.valueOf(enabled));
        }

        if (totalSizeCount != null) {
            long totalBytes = 0;
            if (mods != null) {
                for (Mod mod : mods) {
                    File entry = mod.getEntryPath() == null ? null : new File(mod.getEntryPath());
                    if (entry != null && entry.isFile()) {
                        totalBytes += entry.length();
                    }
                }
            }
            totalSizeCount.setText(android.text.format.Formatter.formatShortFileSize(this, totalBytes));
        }

        GameVersion version = currentVersion();
        String instance = version == null ? null
                : (version.displayName != null && !version.displayName.trim().isEmpty()
                        ? version.displayName : version.directoryName);
        subtitle.setText(instance == null
                ? getString(R.string.mods_subtitle_counts, total, enabled)
                : getString(R.string.mods_subtitle, instance, total, enabled));
    }

    private void showAddModDialog() {
        View content = getLayoutInflater().inflate(R.layout.dialog_add_mod, null);
        CustomAlertDialog dialog = new CustomAlertDialog(this)
                .setTitleText(getString(R.string.add_mod))
                .setCustomView(content)
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null);
        content.findViewById(R.id.import_option_device).setOnClickListener(v -> {
            dialog.dismiss();
            startFilePicker();
        });
        content.findViewById(R.id.import_option_github).setOnClickListener(v -> {
            dialog.dismiss();
            showGitHubImportDialog();
        });
        content.findViewById(R.id.import_option_mediafire).setOnClickListener(v -> {
            dialog.dismiss();
            showMediaFireImportDialog();
        });
        dialog.show();
    }

    private void showGitHubImportDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_github_import, null);
        EditText repoEdit = dialogView.findViewById(R.id.github_repo_edit);
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.github_import_dialog_title))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.github_import_action), v -> {
                    String ownerRepo = GitHubModRepository.parseRepoRef(repoEdit.getText().toString());
                    if (ownerRepo == null) {
                        Toast.makeText(this, R.string.github_import_invalid_repo, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    fetchLatestForImport(ownerRepo);
                })
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    private void showMediaFireImportDialog() {
        View dialogView = getLayoutInflater().inflate(R.layout.dialog_mediafire_import, null);
        EditText linkEdit = dialogView.findViewById(R.id.mediafire_link_edit);
        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.mediafire_import_dialog_title))
                .setCustomView(dialogView)
                .setPositiveButton(getString(R.string.github_import_action), v -> {
                    String link = MediaFireModInstaller.normalizeLink(linkEdit.getText().toString());
                    if (link == null) {
                        Toast.makeText(this, R.string.mediafire_import_invalid_link, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    installFromMediaFire(link);
                })
                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                .show();
    }

    private void installFromMediaFire(String link) {
        mediaFireModInstaller.install(link, selectedVersionCode, new MediaFireModInstaller.Callback() {
            @Override
            public void onWarning(String message, Runnable proceed) {
                new CustomAlertDialog(ModsFullscreenActivity.this)
                        .setTitleText(getString(R.string.mediafire_import_dialog_title))
                        .setMessage(message)
                        .setPositiveButton(getString(R.string.confirm), d -> proceed.run())
                        .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                        .show();
            }

            @Override
            public void onSuccess() {
                Toast.makeText(ModsFullscreenActivity.this,
                        R.string.mediafire_import_success, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ModsFullscreenActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void fetchLatestForImport(String ownerRepo) {
        GitHubModRepository.fetchLatestRelease(this, ownerRepo, new GitHubModRepository.ReleaseCallback() {
            @Override
            public void onRelease(GitHubModRepository.GitHubRelease release) {
                List<GitHubModRepository.GitHubAsset> assets = release.supportedAssets();
                if (assets.isEmpty()) {
                    Toast.makeText(ModsFullscreenActivity.this,
                            R.string.github_import_no_assets, Toast.LENGTH_LONG).show();
                    return;
                }
                if (assets.size() == 1) {
                    installFromGitHub(ownerRepo, assets.get(0), release.tagName, null, false);
                    return;
                }
                String[] names = new String[assets.size()];
                for (int i = 0; i < assets.size(); i++) {
                    names[i] = assets.get(i).name;
                }
                new CustomAlertDialog(ModsFullscreenActivity.this)
                        .setTitleText(getString(R.string.github_import_choose_asset))
                        .setItems(names, (d, which) ->
                                installFromGitHub(ownerRepo, assets.get(which), release.tagName, null, false))
                        .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                        .show();
            }

            @Override
            public void onError(String message) {
                Toast.makeText(ModsFullscreenActivity.this, message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void installFromGitHub(String ownerRepo, GitHubModRepository.GitHubAsset asset,
                                   String versionTag, String nameOverride, boolean forceOverwrite) {
        githubModInstaller.install(ownerRepo, asset, versionTag, nameOverride, selectedVersionCode,
                forceOverwrite, new GitHubModInstaller.Callback() {
                    @Override
                    public void onWarning(String message, Runnable proceed) {
                        new CustomAlertDialog(ModsFullscreenActivity.this)
                                .setTitleText(getString(R.string.github_import_dialog_title))
                                .setMessage(message)
                                .setPositiveButton(getString(R.string.confirm), d -> proceed.run())
                                .setNegativeButton(getString(R.string.dialog_negative_cancel), null)
                                .show();
                    }

                    @Override
                    public void onSuccess() {
                        Toast.makeText(ModsFullscreenActivity.this,
                                forceOverwrite
                                        ? getString(R.string.external_mods_installed,
                                                nameOverride != null ? nameOverride : asset.name)
                                        : getString(R.string.github_import_success),
                                Toast.LENGTH_SHORT).show();
                        if (forceOverwrite) finishGitHubBatchItem();
                    }

                    @Override
                    public void onError(String message) {
                        Toast.makeText(ModsFullscreenActivity.this, message, Toast.LENGTH_LONG).show();
                        if (forceOverwrite) finishGitHubBatchItem();
                    }
                });
    }

    private void checkGitHubUpdates(List<Mod> mods) {
        if (githubUpdateCheckRunning || githubBatchUpdating || mods == null) return;
        List<Mod> githubMods = new ArrayList<>();
        for (Mod mod : mods) {
            if (mod.getGithubRepo() != null && !mod.getGithubRepo().isEmpty()) {
                githubMods.add(mod);
            }
        }
        if (githubMods.isEmpty()) return;
        githubUpdateCheckRunning = true;
        Map<String, String> found = new LinkedHashMap<>();
        AtomicInteger remaining = new AtomicInteger(githubMods.size());
        for (Mod mod : githubMods) {
            GitHubModRepository.fetchLatestRelease(this, mod.getGithubRepo(), new GitHubModRepository.ReleaseCallback() {
                @Override
                public void onRelease(GitHubModRepository.GitHubRelease release) {
                    String installed = mod.getVersion() == null ? "" : mod.getVersion();
                    if (release.tagName != null
                            && GithubReleaseUpdater.compareVersion(release.tagName, installed) > 0) {
                        found.put(mod.getId(), release.tagName);
                    }
                    finishGitHubUpdateCheck(remaining, found);
                }

                @Override
                public void onError(String message) {
                    finishGitHubUpdateCheck(remaining, found);
                }
            });
        }
    }

    private void finishGitHubUpdateCheck(AtomicInteger remaining, Map<String, String> found) {
        if (remaining.decrementAndGet() != 0) return;
        githubUpdateCheckRunning = false;
        modsAdapter.setUpdateVersions(found);
        if (!found.isEmpty()) showGitHubUpdatesDialog(found);
    }

    private void showGitHubUpdatesDialog(Map<String, String> updates) {
        String signature = new TreeMap<>(updates).toString();
        if (signature.equals(lastShownUpdateSignature)) return;
        lastShownUpdateSignature = signature;

        StringBuilder lines = new StringBuilder();
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        if (mods != null) {
            for (Mod mod : mods) {
                String tag = updates.get(mod.getId());
                if (tag != null) {
                    lines.append("• ").append(mod.getDisplayName()).append(" → ").append(tag).append('\n');
                }
            }
        }
        if (lines.length() == 0) return;

        new CustomAlertDialog(this)
                .setTitleText(getString(R.string.github_updates_title))
                .setMessage(getString(R.string.github_updates_message, lines.toString().trim()))
                .setPositiveButton(getString(R.string.github_updates_install_all), v -> installGitHubUpdates(updates))
                .setNegativeButton(getString(R.string.github_updates_later), null)
                .show();
    }

    private void installGitHubUpdates(Map<String, String> updates) {
        List<Mod> mods = viewModel.getModsLiveData().getValue();
        if (mods == null || updates.isEmpty()) return;
        lastShownUpdateSignature = "";
        githubBatchUpdating = true;
        githubBatchRemaining = updates.size();
        for (Mod mod : mods) {
            if (!updates.containsKey(mod.getId()) || mod.getGithubRepo() == null) continue;
            GitHubModRepository.fetchLatestRelease(this, mod.getGithubRepo(), new GitHubModRepository.ReleaseCallback() {
                @Override
                public void onRelease(GitHubModRepository.GitHubRelease release) {
                    GitHubModRepository.GitHubAsset asset = pickGitHubAsset(release, mod.getGithubAsset());
                    if (asset == null) {
                        finishGitHubBatchItem();
                        return;
                    }
                    installFromGitHub(mod.getGithubRepo(), asset, release.tagName, mod.getDisplayName(), true);
                }

                @Override
                public void onError(String message) {
                    finishGitHubBatchItem();
                }
            });
        }
    }

    private void finishGitHubBatchItem() {
        if (--githubBatchRemaining > 0) return;
        githubBatchUpdating = false;
        if (viewModel != null) viewModel.refreshMods();
    }

    private static GitHubModRepository.GitHubAsset pickGitHubAsset(GitHubModRepository.GitHubRelease release,
                                                                  String preferredName) {
        List<GitHubModRepository.GitHubAsset> assets = release.supportedAssets();
        if (preferredName != null) {
            for (GitHubModRepository.GitHubAsset asset : assets) {
                if (preferredName.equalsIgnoreCase(asset.name)) return asset;
            }
        }
        return assets.isEmpty() ? null : assets.get(0);
    }

    @Override
    protected void onResume() {
        super.onResume();
        syncModMenuSwitch();
        if (viewModel != null) {
            viewModel.refreshMods();
        }
        updateModsCount();
    }

}
