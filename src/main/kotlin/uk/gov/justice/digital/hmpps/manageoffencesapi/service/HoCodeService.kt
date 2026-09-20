package uk.gov.justice.digital.hmpps.manageoffencesapi.service

import com.fasterxml.jackson.annotation.JsonProperty
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.HoCodesLoadHistory
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.Offence
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.OffenceToSyncWithNomis
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.PreviousOffenceToHoCodeMapping
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.AnalyticalPlatformTableName.HO_CODES
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.AnalyticalPlatformTableName.HO_CODES_TO_OFFENCE_MAPPING
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.Feature
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.NomisSyncType.HO_CODE_UPDATE
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.HoCodesLoadHistoryRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.HomeOfficeCodeRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.OffenceRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.OffenceToSyncWithNomisRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.PreviousOffenceToHoCodeMappingRepository
import java.time.LocalDateTime
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.HomeOfficeCode as HomeOfficeCodeEntity

@Service
class HoCodeService(
  private val awsS3Service: AwsS3Service,
  private val homeOfficeCodeRepository: HomeOfficeCodeRepository,
  private val hoCodesLoadHistoryRepository: HoCodesLoadHistoryRepository,
  private val offenceRepository: OffenceRepository,
  private val adminService: AdminService,
  private val previousMappingRepository: PreviousOffenceToHoCodeMappingRepository,
  private val offenceToSyncWithNomisRepository: OffenceToSyncWithNomisRepository,
) {
  private val log = LoggerFactory.getLogger(this::class.java)

  // This does a full load whenever there is a new directory in the S3 bucket - at the moment the AP side creates a new release directory with all the data every cycle
  @Scheduled(cron = "0 0 5,17 * * *")
  @Transactional
  @SchedulerLock(name = "fullLoadOfHomeOfficeCodes")
  fun fullLoadOfHomeOfficeCodes() {
    if (!adminService.isFeatureEnabled(Feature.SYNC_HOME_OFFICE_CODES)) {
      log.info("Sync Home Office Codes not running - disabled")
      return
    }

    log.info("Start a full load of Home Office Code data from Analytical Platform (S3)")
    loadHoCodes()
    loadMappingData()
    log.info("Finished full load of Home Office Code data from Analytical Platform (S3)")
  }

  private fun loadHoCodes() {
    val pathToReadFrom = getLatestLoadDirectory(HO_CODES.s3BasePath)
    val hoCodeFileKeys = awsS3Service.getKeysInPath(pathToReadFrom)
    val alreadyLoadedFiles = hoCodesLoadHistoryRepository.findByLoadedFileIn(hoCodeFileKeys)
    log.info("${alreadyLoadedFiles.size} ho-code files have previously been loaded")

    val filesToProcess = hoCodeFileKeys.minus(alreadyLoadedFiles.map { it.loadedFile }.toSet())
    log.info("${filesToProcess.size} ho-code files to process")

    filesToProcess.forEach { fileKey ->
      log.info("Processing $fileKey")
      val hoCodesToLoad =
        awsS3Service.loadParquetFileContents(fileKey, HO_CODES.mappingClass)
          .map { it as HomeOfficeCode }
          .filter { it.latestRecord }
      val hoCodesToSave = hoCodesToLoad.map {
        HomeOfficeCodeEntity(
          id = it.code,
          category = it.category,
          subCategory = it.subCategory,
          description = it.description,
        )
      }
      homeOfficeCodeRepository.saveAll(hoCodesToSave)
      hoCodesLoadHistoryRepository.save(HoCodesLoadHistory(loadedFile = fileKey))
    }
  }

  private fun loadMappingData() {
    val offences = offenceRepository.findByCategoryIsNotNullAndSubCategoryIsNotNull()
    val previousMappings = offences.map {
      PreviousOffenceToHoCodeMapping(
        offenceCode = it.code,
        category = it.category!!,
        subCategory = it.subCategory!!,
      )
    }
    val previousMappingsByOffenceCode = previousMappings.associateBy { it.offenceCode }
    previousMappingRepository.saveAll(previousMappings)
    val pathToReadFrom = getLatestLoadDirectory(HO_CODES_TO_OFFENCE_MAPPING.s3BasePath)
    val mappingFileKeys = awsS3Service.getKeysInPath(pathToReadFrom)
    val alreadyLoadedFiles = hoCodesLoadHistoryRepository.findByLoadedFileIn(mappingFileKeys)
    log.info("${alreadyLoadedFiles.size} mapping files have previously been loaded")
    val filesToProcess = mappingFileKeys.minus(alreadyLoadedFiles.map { it.loadedFile }.toSet())
    log.info("${filesToProcess.size} mapping files to process")

    val mappedCodesInRelease = mutableSetOf<String>()

    filesToProcess.forEach { fileKey ->
      log.info("Processing $fileKey")
      val mappingsToLoad =
        awsS3Service.loadParquetFileContents(fileKey, HO_CODES_TO_OFFENCE_MAPPING.mappingClass)
          .map { it as HomeOfficeCodeToOffenceMapping }
          .filter { it.latestRecord }
      val mappingsByCode = mappingsToLoad.associateBy { it.offenceCode }
      mappedCodesInRelease.addAll(mappingsByCode.keys)
      val offencesToUpdate = offenceRepository.findByCodeIgnoreCaseIn(
        mappingsToLoad
          .map { it.offenceCode }.toSet(),
      )
        .map {
          it.copy(
            category = mappingsByCode[it.code]!!.category,
            subCategory = mappingsByCode[it.code]!!.subCategory,
          )
        }

      offenceRepository.saveAll(offencesToUpdate)
      queueHoCodeChangesForNomis(offencesToUpdate, previousMappingsByOffenceCode)
      hoCodesLoadHistoryRepository.save(HoCodesLoadHistory(loadedFile = fileKey))
    }

    if (filesToProcess.isNotEmpty() && filesToProcess.size == mappingFileKeys.size) {
      inheritHoCodesForChildOffences(mappedCodesInRelease, previousMappingsByOffenceCode)
    } else if (filesToProcess.isNotEmpty()) {
      log.info("Not re-inheriting HO codes for child offences - only ${filesToProcess.size} of ${mappingFileKeys.size} mapping files were processed")
    }
  }

  private fun inheritHoCodesForChildOffences(
    mappedCodesInRelease: Set<String>,
    previousMappingsByOffenceCode: Map<String, PreviousOffenceToHoCodeMapping>,
  ) {
    offenceRepository.flush()
    val childOffences = offenceRepository.findByParentOffenceIdIsNotNull()
    val parentsById = offenceRepository.findAllById(childOffences.mapNotNull { it.parentOffenceId }.toSet())
      .associateBy { it.id }

    val offencesToUpdate = childOffences
      .filter { !mappedCodesInRelease.contains(it.code) }
      .mapNotNull { child ->
        val parent = parentsById[child.parentOffenceId] ?: return@mapNotNull null
        if (child.isEncouragementOf(parent)) return@mapNotNull null
        if (parent.category == null) return@mapNotNull null
        if (child.category == parent.category && child.subCategory == parent.subCategory) return@mapNotNull null
        child.copy(category = parent.category, subCategory = parent.subCategory)
      }

    if (offencesToUpdate.isEmpty()) return
    log.info("There are ${offencesToUpdate.size} child offences inheriting the HO code of their parent")
    offenceRepository.saveAll(offencesToUpdate)
    queueHoCodeChangesForNomis(offencesToUpdate, previousMappingsByOffenceCode)
  }

  private fun queueHoCodeChangesForNomis(
    offencesToUpdate: List<Offence>,
    previousMappingsByOffenceCode: Map<String, PreviousOffenceToHoCodeMapping>,
  ) {
    val offencesToSyncWithNomis = offencesToUpdate
      .filter { it.homeOfficeStatsCode != previousMappingsByOffenceCode[it.code]?.homeOfficeCode }
      .filter { !offenceToSyncWithNomisRepository.existsByOffenceCodeAndNomisSyncType(it.code, HO_CODE_UPDATE) }
      .map {
        OffenceToSyncWithNomis(
          offenceCode = it.code,
          nomisSyncType = HO_CODE_UPDATE,
        )
      }
    if (offencesToSyncWithNomis.isEmpty()) return
    log.info("There are ${offencesToSyncWithNomis.size} offences with HO Code changes that need updating in NOMIS")
    offenceToSyncWithNomisRepository.saveAll(offencesToSyncWithNomis)
  }

  private fun getLatestLoadDirectory(s3BasePath: String): String {
    val subdirectories = awsS3Service.getSubDirectories(s3BasePath)
    val pathByTimestamp = subdirectories
      .associateBy {
        LocalDateTime.parse(
          it.prefix().substring(s3BasePath.length + FOLDER_NAME_PREFIX.length, it.prefix().lastIndex),
        )
      }
    val maxExtractionTimestamp = pathByTimestamp.keys.max()
    log.info("latest extraction timestamp is: $maxExtractionTimestamp")
    return pathByTimestamp[maxExtractionTimestamp]!!.prefix()
  }

  companion object {
    const val FOLDER_NAME_PREFIX = "extraction_timestamp="
  }
}

data class HomeOfficeCode(
  @JsonProperty("ho_offence_code")
  val code: String = "",
  @JsonProperty("ho_offence_desc")
  val description: String = "",
  @JsonProperty("mojap_latest_record")
  val latestRecord: Boolean = false,
) {
  val category: Int
    get() = code.substring(0, 3).toInt()
  val subCategory: Int
    get() = code.substring(3).toInt()
}

data class HomeOfficeCodeToOffenceMapping(
  @JsonProperty("ho_offence_code")
  val hoCode: String = "",
  @JsonProperty("cjs_offence_code")
  val offenceCode: String = "",
  @JsonProperty("mojap_latest_record")
  val latestRecord: Boolean = false,
) {
  val category: Int
    get() = hoCode.substring(0, 3).toInt()
  val subCategory: Int
    get() = hoCode.substring(3).toInt()
}
