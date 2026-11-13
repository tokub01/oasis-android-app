package de.velwebsolutions.oasis.network.ui.oases

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import de.velwebsolutions.oasis.core.model.Loadable
import de.velwebsolutions.oasis.core.network.ApiError
import de.velwebsolutions.oasis.design.components.AsyncOasisButton
import de.velwebsolutions.oasis.design.components.ConsentCheck
import de.velwebsolutions.oasis.design.components.LoadableView
import de.velwebsolutions.oasis.design.components.OasisButton
import de.velwebsolutions.oasis.design.components.OasisButtonKind
import de.velwebsolutions.oasis.design.components.OasisScreen
import de.velwebsolutions.oasis.design.components.OasisTextField
import de.velwebsolutions.oasis.design.components.PageHeader
import de.velwebsolutions.oasis.design.navigation.LocalNavigator
import de.velwebsolutions.oasis.design.theme.OasisColors
import de.velwebsolutions.oasis.design.theme.OasisType
import de.velwebsolutions.oasis.network.NetworkSignals
import de.velwebsolutions.oasis.network.api.NoContext
import de.velwebsolutions.oasis.network.api.OasisCollection
import de.velwebsolutions.oasis.network.api.OasisCollections
import de.velwebsolutions.oasis.network.data.NetworkRepository
import de.velwebsolutions.oasis.network.ui.NetworkViewModel
import de.velwebsolutions.oasis.network.ui.PostListState
import de.velwebsolutions.oasis.network.ui.apiError
import de.velwebsolutions.oasis.network.ui.attempt
import de.velwebsolutions.oasis.network.ui.components.ButtonFlow
import de.velwebsolutions.oasis.network.ui.components.ConfirmDialog
import de.velwebsolutions.oasis.network.ui.components.FeedHandlers
import de.velwebsolutions.oasis.network.ui.components.NetworkPage
import de.velwebsolutions.oasis.network.ui.components.SectionTitle
import de.velwebsolutions.oasis.network.ui.components.block
import de.velwebsolutions.oasis.network.ui.components.feedSection
import de.velwebsolutions.oasis.network.ui.retry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the oasis page can do with its Sammlungen. */
class OasisCollectionActions(
    val onChoose: (Int?) -> Unit,
    val onOpenForm: (OasisCollection?) -> Unit,
    val onEditForm: ((CollectionForm) -> CollectionForm) -> Unit,
    val onCloseForm: () -> Unit,
    val onSave: suspend () -> Unit,
    val onDelete: (OasisCollection) -> Unit,
    val onAllowMembers: (Boolean) -> Unit
) {
    companion object {
        val None = OasisCollectionActions({}, {}, {}, {}, {}, {}, {})
    }
}

/** The collection part of the oasis page: the list, the chosen one with its Tropfen, and the form. */
class OasisCollectionsUi(
    val collections: OasisCollections?,
    val chosenId: Int?,
    val shown: PostListState<NoContext>?,
    val shownHandlers: FeedHandlers?,
    val form: CollectionForm?,
    val actions: OasisCollectionActions
) {
    /** The chosen collection as listed. */
    val chosen: OasisCollection? get() = collections?.collections?.firstOrNull { it.id == chosenId }

    companion object {
        val None = OasisCollectionsUi(null, null, null, null, null, OasisCollectionActions.None)
    }
}

/**
 * „Sammlungen“ (`#sammlungen`): „Sammlung anlegen“ for those allowed, the form, the collections as chips („Rezepte · 4“),
 * the chosen one with its Tropfen, and – for hosts – „Mitglieder dürfen Sammlungen anlegen“.
 */
internal fun LazyListScope.collectionsSection(ui: OasisCollectionsUi, isHost: Boolean) {
    val collections = ui.collections ?: return
    val actions = ui.actions
    block("collections") {
        var confirmDelete by remember { mutableStateOf<OasisCollection?>(null) }
        Column(Modifier.fillMaxWidth().testTag("oasis.collections"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionTitle("Sammlungen", Modifier.weight(1f))
                if (collections.canCreate && ui.form == null) {
                    OasisButton(
                        "Sammlung anlegen",
                        { actions.onOpenForm(null) },
                        kind = OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.testTag("collection.create")
                    )
                }
            }
            ui.form?.let { CollectionFormView(it, actions) }
            if (collections.collections.isEmpty()) {
                Text(
                    "Noch keine Sammlung. In Sammlungen hebt die Crowd gemeinsam Posts auf, etwa Rezepte oder Tipps.",
                    style = OasisType.Body,
                    color = OasisColors.Muted
                )
            }
            ButtonFlow {
                collections.collections.forEach { collection ->
                    val chosen = collection.id == ui.chosenId
                    OasisButton(
                        "${collection.name} · ${collection.postsCount}",
                        { actions.onChoose(collection.id) },
                        kind = if (chosen) OasisButtonKind.Primary else OasisButtonKind.Secondary,
                        fullWidth = false,
                        modifier = Modifier.semantics { selected = chosen }.testTag("collection.${collection.id}")
                    )
                }
            }
            ui.chosen?.let { chosen ->
                Text(chosen.name, style = OasisType.heading(19), color = OasisColors.Night)
                chosen.description?.let { Text(it, style = OasisType.Body, color = OasisColors.Ink2) }
                if (chosen.canEdit) {
                    ButtonFlow {
                        OasisButton("Ändern", {
                            actions.onOpenForm(chosen)
                        }, kind = OasisButtonKind.Secondary, fullWidth = false, modifier = Modifier.testTag("collection.edit"))
                        OasisButton("Sammlung löschen", {
                            confirmDelete = chosen
                        }, kind = OasisButtonKind.Secondary, fullWidth = false, modifier = Modifier.testTag("collection.delete"))
                    }
                }
            }
            if (isHost) {
                ConsentCheck(
                    "Mitglieder dürfen Sammlungen anlegen",
                    collections.membersCreateCollections,
                    actions.onAllowMembers,
                    Modifier.testTag("collection.allowMembers")
                )
            }
        }
        confirmDelete?.let { collection ->
            ConfirmDialog(
                "Sammlung löschen?",
                "Löschen",
                onConfirm = { actions.onDelete(collection) },
                onDismiss = { confirmDelete = null },
                message = "Ihre Posts bleiben in der Crowd."
            )
        }
    }
    val shown = ui.shown
    val handlers = ui.shownHandlers
    if (ui.chosen != null && shown != null && handlers != null) {
        feedSection(
            shown,
            handlers,
            empty = "Noch kein Post in dieser Sammlung. Füge einen über „⋯“ an einem Post hinzu.",
            keyPrefix = "collection"
        )
    }
}

/** „Neue Sammlung“ / „Sammlung ändern“: name and description. */
@Composable
internal fun CollectionFormView(form: CollectionForm, actions: OasisCollectionActions) {
    val creating = form.editing == null
    Column(Modifier.fillMaxWidth().testTag("collection.form"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(if (creating) "Neue Sammlung" else "Sammlung ändern", style = OasisType.heading(17), color = OasisColors.Night)
        OasisTextField(
            "Name",
            form.name,
            { value -> actions.onEditForm { it.copy(name = value) } },
            Modifier.testTag("collection.name"),
            prompt = "z. B. Rezepte",
            error = form.error?.message("name"),
            maxLength = 60
        )
        OasisTextField(
            "Beschreibung (optional)",
            form.description,
            { value -> actions.onEditForm { it.copy(description = value) } },
            Modifier.testTag("collection.description"),
            error = form.error?.message("description"),
            lines = 2..5,
            maxLength = 300
        )
        form.error?.takeIf {
            it !is ApiError.Validation
        }?.let { Text(it.userMessage, style = OasisType.Small, color = OasisColors.SunText) }
        ButtonFlow {
            AsyncOasisButton(
                if (creating) "Sammlung anlegen" else "Änderung speichern",
                actions.onSave,
                fullWidth = false,
                modifier = Modifier.testTag("collection.save")
            )
            OasisButton(
                "Abbrechen",
                actions.onCloseForm,
                kind = OasisButtonKind.Secondary,
                fullWidth = false,
                modifier = Modifier.testTag("collection.close")
            )
        }
    }
}

/** „Zu Sammlung hinzufügen“ (a sheet): the collections of the Tropfen's oasis it may go into. */
@HiltViewModel(assistedFactory = CollectPostViewModel.Factory::class)
class CollectPostViewModel @AssistedInject constructor(
    @Assisted val postId: Int,
    @Assisted val slug: String,
    private val repository: NetworkRepository
) : NetworkViewModel() {
    @AssistedFactory
    interface Factory {
        fun create(postId: Int, slug: String): CollectPostViewModel
    }

    private val mutableCollections = MutableStateFlow<Loadable<List<OasisCollection>>>(Loadable.Idle)

    /** The collections a Tropfen may go into. */
    val collections: StateFlow<Loadable<List<OasisCollection>>> = mutableCollections.asStateFlow()
    private val mutableDone = MutableStateFlow(false)

    /** Set once the Tropfen went into a collection: the sheet closes. */
    val done: StateFlow<Boolean> = mutableDone.asStateFlow()

    suspend fun load() {
        mutableCollections.load { repository.collections(slug).collections.filter { it.canAdd } }
    }

    /** Adds the Tropfen; the tab shows the confirmation, oasis pages show the collections again. */
    suspend fun add(collection: OasisCollection) {
        val result = attempt { repository.collectPost(collection.id, postId) }
        mutableError.value = result.apiError
        val answer = result.getOrNull() ?: return
        NetworkSignals.confirmation.value = answer.message
        NetworkSignals.didChangeCollections()
        mutableDone.value = true
    }
}

/** The sheet „Zu Sammlung hinzufügen“. */
@Composable
fun CollectPostScreen(
    postId: Int,
    slug: String,
    viewModel: CollectPostViewModel = hiltViewModel<CollectPostViewModel, CollectPostViewModel.Factory>(
        key = "collect-$postId-$slug",
        creationCallback = { it.create(postId, slug) }
    )
) {
    val collections by viewModel.collections.collectAsStateWithLifecycle()
    val done by viewModel.done.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()
    val navigator = LocalNavigator.current
    LaunchedEffect(Unit) { viewModel.load() }
    LaunchedEffect(done) { if (done) navigator.dismissSheet() }
    OasisScreen(title = "Zu Sammlung hinzufügen", error = error?.userMessage, onDismissError = viewModel::dismissError) {
        LoadableView(collections, onRetry = viewModel.retry(viewModel::load)) { list ->
            NetworkPage {
                block("header") { PageHeader("Zu Sammlung hinzufügen", eyebrow = "Sammlungen") }
                block("list") {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (list.isEmpty()) {
                            Text(
                                "Hier gibt es noch keine Sammlung, in die du ihn legen kannst.",
                                style = OasisType.Body,
                                color = OasisColors.Muted
                            )
                        }
                        list.forEach { collection ->
                            AsyncOasisButton(
                                "${collection.name} · ${collection.postsCount}",
                                { viewModel.add(collection) },
                                kind = OasisButtonKind.Secondary,
                                modifier = Modifier.testTag("collect.${collection.id}")
                            )
                        }
                    }
                }
            }
        }
    }
}
