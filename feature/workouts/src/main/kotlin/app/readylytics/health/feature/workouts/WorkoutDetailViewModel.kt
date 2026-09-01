package app.readylytics.health.feature.workouts

import androidx.compose.runtime.Immutable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.readylytics.health.core.model.data.preferences.SettingsDefaults
import app.readylytics.health.core.model.domain.layout.LayoutManagementDelegate
import app.readylytics.health.core.model.domain.model.DomainRouteLocation
import app.readylytics.health.core.model.domain.preferences.UnitSystem
import app.readylytics.health.core.model.domain.preferences.UserPreferencesReader
import app.readylytics.health.core.model.domain.repository.WorkoutData
import app.readylytics.health.core.model.domain.sync.SyncWorkoutRouteUseCase
import app.readylytics.health.core.model.domain.workouts.WorkoutDetailLayoutRepository
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemConfiguration
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutDetailItemId
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutType
import app.readylytics.health.core.model.domain.workouts.detail.WorkoutLayoutTypeMapper
import app.readylytics.health.core.scoring.domain.scoring.WorkoutLoadClassification
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@Immutable
data class WorkoutDetailUiState(
    val workout: WorkoutData? = null,
    val hrSamples: List<HeartRatePoint> = emptyList(),
    val hrChartData: List<Pair<Double, Double>> = emptyList(),
    val durationMinutes: Int = 0,
    val hrr1Min: Int? = null,
    val hrr2Min: Int? = null,
    val hrr3Min: Int? = null,
    val totalRas: Float? = null,
    val rasDailyBreakdown: List<Pair<String, Float>> = emptyList(),
    val computedTrimp: Int? = null,
    val gainedStrain: Float? = null,
    val gainedStrainDisplay: String = "—",
    val ras: Float? = null,
    val classification: WorkoutLoadClassification? = null,
    val routeUiState: RouteUiState = RouteUiState(),
    val paceSpeedChartData: List<Pair<Double, Double>> = emptyList(),
    val elevationChartData: List<Pair<Double, Double>> = emptyList(),
    val displayElevationGainMeters: Float? = null,
    val isPaceMode: Boolean = false,
    val unitSystem: UnitSystem = UnitSystem.METRIC,
    val isLoading: Boolean = true,
    val layoutType: WorkoutLayoutType = WorkoutLayoutType.OTHER,
    val itemConfigurations: List<WorkoutDetailItemConfiguration> = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS,
    val isManagingLayout: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WorkoutDetailViewModel
    @Inject
    constructor(
        private val workoutDetailLoader: WorkoutDetailLoader,
        private val settingsRepo: UserPreferencesReader,
        private val syncWorkoutRouteUseCase: SyncWorkoutRouteUseCase,
        private val workoutDetailLayoutRepository: WorkoutDetailLayoutRepository,
        private val savedStateHandle: SavedStateHandle,
    ) : ViewModel() {
        private val _uiState = MutableStateFlow(WorkoutDetailUiState())
        val uiState = _uiState.asStateFlow()

        private val layoutType = MutableStateFlow(WorkoutLayoutType.OTHER)

        private val layoutDelegate =
            LayoutManagementDelegate(
                defaultConfigurations = SettingsDefaults.DEFAULT_WORKOUT_DETAIL_ITEMS,
                persist = { configs -> workoutDetailLayoutRepository.updateLayout(layoutType.value, configs) },
                scope = viewModelScope,
                withVisibility = { config, visible -> config.copy(isVisible = visible) },
                withPosition = { config, pos -> config.copy(position = pos) },
            )

        init {
            savedStateHandle.get<String>("workoutId")?.let { id ->
                loadWorkout(id)
            }
        }

        init {
            viewModelScope.launch {
                layoutType
                    .flatMapLatest { type -> workoutDetailLayoutRepository.layoutFor(type) }
                    .combine(layoutDelegate.state) { stored, managementState ->
                        (managementState.pendingConfigs ?: stored) to managementState.isManaging
                    }.collect { (configs, isManaging) ->
                        _uiState.update {
                            it.copy(itemConfigurations = configs, isManagingLayout = isManaging)
                        }
                    }
            }
        }

        fun onToggleLayoutManagement() {
            if (_uiState.value.isManagingLayout) {
                layoutDelegate.saveChanges()
            } else {
                layoutDelegate.enterEditMode(_uiState.value.itemConfigurations)
            }
        }

        fun onCancelLayoutManagement() {
            layoutDelegate.cancelChanges()
        }

        fun onToggleItemVisibility(
            itemId: WorkoutDetailItemId,
            visible: Boolean,
        ) {
            layoutDelegate.onToggleVisibility(_uiState.value.itemConfigurations, itemId, visible)
        }

        fun onReorderItems(newOrder: List<WorkoutDetailItemConfiguration>) {
            layoutDelegate.onReorder(_uiState.value.itemConfigurations, newOrder)
        }

        fun onResetLayoutToDefaults() {
            layoutDelegate.onResetToDefaults()
        }

        /**
         * @param grantedRoutePoints polyline returned by the per-session consent dialog. Empty when
         * the user granted the bulk `READ_EXERCISE_ROUTES` permission instead, in which case the
         * session re-read inside [syncWorkoutRouteUseCase] carries the route.
         */
        fun onRoutePermissionResult(grantedRoutePoints: List<DomainRouteLocation> = emptyList()) {
            val workoutId = savedStateHandle.get<String>("workoutId") ?: return
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                syncWorkoutRouteUseCase(workoutId, grantedRoutePoints.takeIf { it.isNotEmpty() })
                loadWorkout(workoutId)
            }
        }

        fun loadWorkout(workoutId: String) {
            savedStateHandle["workoutId"] = workoutId
            viewModelScope.launch {
                _uiState.update { it.copy(isLoading = true) }
                val prefs = settingsRepo.userPreferences.first()
                val data = workoutDetailLoader.load(workoutId, prefs)
                if (data == null) {
                    _uiState.update { it.copy(isLoading = false, workout = null) }
                    return@launch
                }

                layoutType.value = WorkoutLayoutTypeMapper.fromExerciseType(data.workout.exerciseType)

                _uiState.update { currentState ->
                    currentState.copy(
                        workout = data.workout,
                        hrSamples = data.hrSamples,
                        hrChartData = data.hrChartData,
                        durationMinutes = data.durationMinutes,
                        hrr1Min = data.hrr1Min,
                        hrr2Min = data.hrr2Min,
                        hrr3Min = data.hrr3Min,
                        totalRas = data.totalRas,
                        rasDailyBreakdown = data.rasDailyBreakdown,
                        computedTrimp = data.computedTrimp,
                        gainedStrain = data.gainedStrain,
                        gainedStrainDisplay = data.gainedStrainDisplay,
                        ras = data.ras,
                        classification = data.classification,
                        routeUiState = data.routeUiState,
                        paceSpeedChartData = data.paceSpeedChartData,
                        elevationChartData = data.elevationChartData,
                        displayElevationGainMeters = data.displayElevationGainMeters,
                        isPaceMode = data.isPaceMode,
                        unitSystem = data.unitSystem,
                        layoutType = layoutType.value,
                        isLoading = false,
                    )
                }
            }
        }
    }
