package uk.gov.justice.digital.hmpps.manageoffencesapi.service

import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argThat
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import software.amazon.awssdk.services.s3.model.CommonPrefix
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.Offence
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.OffenceToSyncWithNomis
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.PreviousOffenceToHoCodeMapping
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.AnalyticalPlatformTableName.HO_CODES
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.AnalyticalPlatformTableName.HO_CODES_TO_OFFENCE_MAPPING
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.Feature
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.NomisSyncType
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.SdrsCache
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.HoCodesLoadHistoryRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.HomeOfficeCodeRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.OffenceRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.OffenceToSyncWithNomisRepository
import uk.gov.justice.digital.hmpps.manageoffencesapi.repository.PreviousOffenceToHoCodeMappingRepository
import java.time.LocalDate
import java.time.LocalDateTime

class HoCodeServiceTest {

  private val awsS3Service = mock<AwsS3Service>()
  private val homeOfficeCodeRepository = mock<HomeOfficeCodeRepository>()
  private val hoCodesLoadHistoryRepository = mock<HoCodesLoadHistoryRepository>()
  private val offenceRepository = mock<OffenceRepository>()
  private val previousMappingRepository = mock<PreviousOffenceToHoCodeMappingRepository>()
  private val offenceToSyncWithNomisRepository = mock<OffenceToSyncWithNomisRepository>()
  private val adminService = mock<AdminService>()
  private val hoCodeService =
    HoCodeService(
      awsS3Service,
      homeOfficeCodeRepository,
      hoCodesLoadHistoryRepository,
      offenceRepository,
      adminService,
      previousMappingRepository,
      offenceToSyncWithNomisRepository,
    )

  @Nested
  inner class FullLoadTests {
    @Test
    fun `Do not run load if feature toggle is disabled`() {
      whenever(adminService.isFeatureEnabled(Feature.SYNC_HOME_OFFICE_CODES)).thenReturn(false)
      hoCodeService.fullLoadOfHomeOfficeCodes()
      verifyNoInteractions(awsS3Service)
    }

    @Test
    fun `Test that a full load runs successfully - loads latest ho-codes and mappings`() {
      whenever(adminService.isFeatureEnabled(Feature.SYNC_HOME_OFFICE_CODES)).thenReturn(true)
      whenever(awsS3Service.getSubDirectories(HO_CODES.s3BasePath)).thenReturn(SUB_DIRECTORIES_HO_CODE)
      whenever(awsS3Service.getSubDirectories(HO_CODES_TO_OFFENCE_MAPPING.s3BasePath)).thenReturn(
        SUB_DIRECTORIES_MAPPINGS,
      )

      whenever(awsS3Service.getKeysInPath(LATEST_FOLDER_PATH_HO_CODE)).thenReturn(setOf(HO_FILE_1_KEY))
      whenever(awsS3Service.getKeysInPath(LATEST_FOLDER_PATH_MAPPINGS)).thenReturn(setOf(MAPPING_FILE_1_KEY))

      whenever(hoCodesLoadHistoryRepository.findByLoadedFileIn(any())).thenReturn(emptySet())

      whenever(awsS3Service.loadParquetFileContents(HO_FILE_1_KEY, HO_CODES.mappingClass)).thenReturn(
        listOf(
          HOME_OFFICE_CODE_1,
          HOME_OFFICE_CODE_1_OLD,
        ),
      )
      whenever(
        awsS3Service.loadParquetFileContents(
          MAPPING_FILE_1_KEY,
          HO_CODES_TO_OFFENCE_MAPPING.mappingClass,
        ),
      ).thenReturn(listOf(MAPPING_1, MAPPING_1_OLD))

      whenever(offenceRepository.findByCodeIgnoreCaseIn(setOf(MAPPING_1.offenceCode))).thenReturn(listOf(OFFENCE_1))

      hoCodeService.fullLoadOfHomeOfficeCodes()

      verify(homeOfficeCodeRepository).saveAll(
        listOf(
          uk.gov.justice.digital.hmpps.manageoffencesapi.entity.HomeOfficeCode(
            id = HOME_OFFICE_CODE_1.code,
            category = HOME_OFFICE_CODE_1.category,
            subCategory = HOME_OFFICE_CODE_1.subCategory,
            description = HOME_OFFICE_CODE_1.description,
          ),
        ),
      )
      verify(offenceRepository).saveAll(
        listOf(
          OFFENCE_1.copy(category = 12, subCategory = 34),
        ),
      )
      verify(hoCodesLoadHistoryRepository).save(
        argThat { hoCodesLoadHistory -> hoCodesLoadHistory.loadedFile == HO_FILE_1_KEY },
      )
      verify(hoCodesLoadHistoryRepository).save(
        argThat { hoCodesLoadHistory -> hoCodesLoadHistory.loadedFile == MAPPING_FILE_1_KEY },
      )
    }
  }

  @Nested
  inner class NomisSyncTests {
    @Test
    fun `Test that any changes are marked to be pushed to Nomis`() {
      whenever(adminService.isFeatureEnabled(Feature.SYNC_HOME_OFFICE_CODES)).thenReturn(true)
      whenever(awsS3Service.getSubDirectories(HO_CODES.s3BasePath)).thenReturn(SUB_DIRECTORIES_HO_CODE)
      whenever(awsS3Service.getSubDirectories(HO_CODES_TO_OFFENCE_MAPPING.s3BasePath)).thenReturn(
        SUB_DIRECTORIES_MAPPINGS,
      )

      whenever(awsS3Service.getKeysInPath(LATEST_FOLDER_PATH_HO_CODE)).thenReturn(setOf(HO_FILE_1_KEY))
      whenever(awsS3Service.getKeysInPath(LATEST_FOLDER_PATH_MAPPINGS)).thenReturn(setOf(MAPPING_FILE_1_KEY))

      whenever(offenceRepository.findByCategoryIsNotNullAndSubCategoryIsNotNull()).thenReturn(
        listOf(OFFENCE_2, OFFENCE_3),
      )

      whenever(hoCodesLoadHistoryRepository.findByLoadedFileIn(any())).thenReturn(emptySet())

      whenever(awsS3Service.loadParquetFileContents(HO_FILE_1_KEY, HO_CODES.mappingClass)).thenReturn(
        listOf(
          HOME_OFFICE_CODE_1,
        ),
      )
      whenever(
        awsS3Service.loadParquetFileContents(
          MAPPING_FILE_1_KEY,
          HO_CODES_TO_OFFENCE_MAPPING.mappingClass,
        ),
      ).thenReturn(listOf(MAPPING_2, MAPPING_3, MAPPING_4))

      whenever(
        offenceRepository.findByCodeIgnoreCaseIn(
          setOf(
            MAPPING_2.offenceCode,
            MAPPING_3.offenceCode,
            MAPPING_4.offenceCode,
          ),
        ),
      ).thenReturn(listOf(OFFENCE_2, OFFENCE_3, OFFENCE_4))
      whenever(offenceToSyncWithNomisRepository.existsByOffenceCodeAndNomisSyncType(OFFENCE_4.code, NomisSyncType.HO_CODE_UPDATE)).thenReturn(true)

      hoCodeService.fullLoadOfHomeOfficeCodes()

      verify(offenceRepository).saveAll(
        listOf(
          OFFENCE_2.copy(category = 567, subCategory = 89),
          OFFENCE_3,
          OFFENCE_4.copy(category = 999, subCategory = 99),
        ),
      )

      verify(previousMappingRepository).saveAll(
        listOf(
          PreviousOffenceToHoCodeMapping(
            offenceCode = OFFENCE_2.code,
            category = OFFENCE_2.category!!,
            subCategory = OFFENCE_2.subCategory!!,
          ),
          PreviousOffenceToHoCodeMapping(
            offenceCode = OFFENCE_3.code,
            category = OFFENCE_3.category!!,
            subCategory = OFFENCE_3.subCategory!!,
          ),
        ),
      )

      val offencesToSyncCaptor = argumentCaptor<List<OffenceToSyncWithNomis>>()
      verify(offenceToSyncWithNomisRepository).saveAll(offencesToSyncCaptor.capture())

      Assertions.assertThat(offencesToSyncCaptor.allValues[0])
        .usingRecursiveComparison()
        .ignoringCollectionOrder()
        .ignoringFields("id", "createdDate")
        .isEqualTo(
          listOf(
            OffenceToSyncWithNomis(
              offenceCode = OFFENCE_2.code,
              nomisSyncType = NomisSyncType.HO_CODE_UPDATE,
            ),
          ),
        )
    }
  }

  @Nested
  inner class ChildOffenceHoCodeInheritanceTests {
    @Test
    fun `Child offences with no mapping of their own inherit the ho code of their parent`() {
      stubReleaseContaining(listOf(MAPPING_2, MAPPING_CHILD_WITH_OWN_MAPPING))

      whenever(
        offenceRepository.findByCodeIgnoreCaseIn(
          setOf(MAPPING_2.offenceCode, MAPPING_CHILD_WITH_OWN_MAPPING.offenceCode),
        ),
      ).thenReturn(listOf(PARENT_OFFENCE, CHILD_WITH_OWN_MAPPING))

      whenever(offenceRepository.findByParentOffenceIdIsNotNull()).thenReturn(
        listOf(CHILD_ATTEMPT, CHILD_WITH_OWN_MAPPING, CHILD_ENCOURAGEMENT, CHILD_OF_UNMAPPED_PARENT),
      )
      whenever(offenceRepository.findAllById(setOf(PARENT_OFFENCE.id, UNMAPPED_PARENT_OFFENCE.id))).thenReturn(
        listOf(PARENT_OFFENCE.copy(category = 567, subCategory = 89), UNMAPPED_PARENT_OFFENCE),
      )

      hoCodeService.fullLoadOfHomeOfficeCodes()

      verify(offenceRepository).saveAll(
        listOf(CHILD_ATTEMPT.copy(category = 567, subCategory = 89)),
      )
    }

    private fun stubReleaseContaining(mappings: List<HomeOfficeCodeToOffenceMapping>) {
      whenever(adminService.isFeatureEnabled(Feature.SYNC_HOME_OFFICE_CODES)).thenReturn(true)
      whenever(awsS3Service.getSubDirectories(HO_CODES.s3BasePath)).thenReturn(SUB_DIRECTORIES_HO_CODE)
      whenever(awsS3Service.getSubDirectories(HO_CODES_TO_OFFENCE_MAPPING.s3BasePath)).thenReturn(SUB_DIRECTORIES_MAPPINGS)
      whenever(awsS3Service.getKeysInPath(LATEST_FOLDER_PATH_HO_CODE)).thenReturn(setOf(HO_FILE_1_KEY))
      whenever(awsS3Service.getKeysInPath(LATEST_FOLDER_PATH_MAPPINGS)).thenReturn(setOf(MAPPING_FILE_1_KEY))
      whenever(hoCodesLoadHistoryRepository.findByLoadedFileIn(any())).thenReturn(emptySet())
      whenever(awsS3Service.loadParquetFileContents(HO_FILE_1_KEY, HO_CODES.mappingClass)).thenReturn(emptyList())
      whenever(
        awsS3Service.loadParquetFileContents(MAPPING_FILE_1_KEY, HO_CODES_TO_OFFENCE_MAPPING.mappingClass),
      ).thenReturn(mappings)
    }
  }

  companion object {
    private val LATEST_FOLDER_PATH_HO_CODE = HO_CODES.s3BasePath + "extraction_timestamp=" + "2023-05-07T03:04:11.984/"
    private val LATEST_FOLDER_PATH_MAPPINGS =
      HO_CODES_TO_OFFENCE_MAPPING.s3BasePath + "extraction_timestamp=" + "2023-05-07T03:04:11.984/"
    val SUB_DIRECTORIES_HO_CODE = listOf(
      CommonPrefix.builder()
        .prefix(LATEST_FOLDER_PATH_HO_CODE)
        .build(),
    )

    val SUB_DIRECTORIES_MAPPINGS = listOf(
      CommonPrefix.builder()
        .prefix(LATEST_FOLDER_PATH_MAPPINGS)
        .build(),
    )

    val HO_FILE_1_KEY = LATEST_FOLDER_PATH_HO_CODE + "ho-code-file1"
    val MAPPING_FILE_1_KEY = LATEST_FOLDER_PATH_MAPPINGS + "mapping-file1"

    val HOME_OFFICE_CODE_1 = HomeOfficeCode(code = "01234", description = "ho description 1", latestRecord = true)
    val HOME_OFFICE_CODE_1_OLD = HomeOfficeCode(code = "01234", description = "ho description 1 old record", latestRecord = false)
    val MAPPING_1 = HomeOfficeCodeToOffenceMapping(hoCode = "01234", offenceCode = "OFF1", latestRecord = true)
    val MAPPING_2 = HomeOfficeCodeToOffenceMapping(hoCode = "56789", offenceCode = "OFF2", latestRecord = true)
    val MAPPING_3 = HomeOfficeCodeToOffenceMapping(hoCode = "05678", offenceCode = "OFF3", latestRecord = true)
    val MAPPING_4 = HomeOfficeCodeToOffenceMapping(hoCode = "99999", offenceCode = "OFF4", latestRecord = true)
    val MAPPING_1_OLD = HomeOfficeCodeToOffenceMapping(hoCode = "99999", offenceCode = "OFF1", latestRecord = false)

    private val BASE_OFFENCE = Offence(
      code = "AABB011",
      changedDate = LocalDateTime.now(),
      revisionId = 1,
      startDate = LocalDate.now(),
      sdrsCache = SdrsCache.OFFENCES_A,
    )
    val OFFENCE_1 = BASE_OFFENCE.copy(code = "OFF1")
    val OFFENCE_2 = BASE_OFFENCE.copy(code = "OFF2", category = 12, subCategory = 34)
    val OFFENCE_3 = BASE_OFFENCE.copy(code = "OFF3", category = 56, subCategory = 78)
    val OFFENCE_4 = BASE_OFFENCE.copy(code = "OFF4")

    val PARENT_OFFENCE = BASE_OFFENCE.copy(id = 1, code = "OFF2")
    val CHILD_ATTEMPT = BASE_OFFENCE.copy(id = 2, code = "OFF2A", parentOffenceId = 1)
    val CHILD_WITH_OWN_MAPPING = BASE_OFFENCE.copy(id = 3, code = "OFF2C", parentOffenceId = 1)
    val CHILD_ENCOURAGEMENT = BASE_OFFENCE.copy(id = 4, code = "OFF2E", parentOffenceId = 1)
    val UNMAPPED_PARENT_OFFENCE = BASE_OFFENCE.copy(id = 5, code = "OFF5")
    val CHILD_OF_UNMAPPED_PARENT = BASE_OFFENCE.copy(id = 6, code = "OFF5A", parentOffenceId = 5)
    val MAPPING_CHILD_WITH_OWN_MAPPING =
      HomeOfficeCodeToOffenceMapping(hoCode = "00200", offenceCode = "OFF2C", latestRecord = true)
  }
}
