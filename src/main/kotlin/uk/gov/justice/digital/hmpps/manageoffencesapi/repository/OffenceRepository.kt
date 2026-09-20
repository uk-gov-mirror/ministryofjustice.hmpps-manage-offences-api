package uk.gov.justice.digital.hmpps.manageoffencesapi.repository

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import uk.gov.justice.digital.hmpps.manageoffencesapi.entity.Offence
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.SdrsCache
import java.util.Optional

@Repository
interface OffenceRepository : JpaRepository<Offence, Long> {
  fun findByCodeStartsWithIgnoreCase(code: String): List<Offence>
  fun findByCodeStartsWithIgnoreCaseOrCjsTitleContainsIgnoreCaseOrLegislationContainsIgnoreCase(
    codeSearch: String,
    descriptionSearch: String,
    legislationSearch: String,
  ): List<Offence>

  fun findByCodeStartsWithIgnoreCaseOrCjsTitleContainsIgnoreCase(
    codeSearch: String,
    descriptionSearch: String,
  ): List<Offence>

  fun findByCategoryAndSubCategory(category: Int, subCategory: Int): List<Offence>

  fun findBySdrsCache(sdrsCache: SdrsCache): List<Offence>
  fun findOneByCode(code: String): Optional<Offence>

  @Query("SELECT o FROM Offence o WHERE o.sdrsCache = :sdrsCache AND LENGTH(o.code) > 7 AND o.parentOffenceId IS NULL")
  fun findChildOffencesWithNoParent(sdrsCache: SdrsCache): List<Offence>

  fun findByParentOffenceId(parentOffenceId: Long): List<Offence>

  fun findByParentOffenceIdIn(parentOffenceIds: Set<Long>): List<Offence>

  fun findByParentOffenceIdIsNotNull(): List<Offence>

  fun deleteByParentOffenceIdIsNotNull()

  fun findByCodeIgnoreCaseIn(codes: Set<String>): List<Offence>

  fun findByCodeIgnoreCase(code: String): Offence?

  fun findByCategoryIsNotNullAndSubCategoryIsNotNull(): List<Offence>

  @Query(
    "SELECT o FROM Offence o WHERE " +
      "LOWER(o.code) LIKE LOWER(CONCAT(:prefix1, '%')) " +
      "OR LOWER(o.code) LIKE LOWER(CONCAT(:prefix2, '%')) " +
      "",
  )
  fun findByCodeStartsWithAnyIgnoreCase(prefix1: String, prefix2: String): List<Offence>

  @Query(
    """
        SELECT o FROM Offence o 
        WHERE 
            LOWER(o.legislation) LIKE LOWER(CONCAT('%', :legislationText1, '%')) OR
            LOWER(o.legislation) LIKE LOWER(CONCAT('%', :legislationText2, '%')) OR
            LOWER(o.legislation) LIKE LOWER(CONCAT('%', :legislationText3, '%')) OR
            LOWER(o.legislation) LIKE LOWER(CONCAT('%', :legislationText4, '%'))
    """,
  )
  fun findByLegislationLikeIgnoreCase(
    @Param("legislationText1") legislationText1: String,
    @Param("legislationText2") legislationText2: String,
    @Param("legislationText3") legislationText3: String,
    @Param("legislationText4") legislationText4: String,
  ): List<Offence>

  @Query("SELECT o FROM Offence o WHERE LOWER(o.legislation) LIKE LOWER(CONCAT('%', :legislationText, '%'))")
  fun findByLegislationLikeIgnoreCase(@Param("legislationText") legislationText: String): List<Offence>
}
