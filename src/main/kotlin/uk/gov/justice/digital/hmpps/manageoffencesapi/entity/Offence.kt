package uk.gov.justice.digital.hmpps.manageoffencesapi.entity

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.JoinColumn
import jakarta.persistence.JoinColumns
import jakarta.persistence.ManyToOne
import jakarta.persistence.Table
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.CustodialIndicator
import uk.gov.justice.digital.hmpps.manageoffencesapi.enum.SdrsCache
import java.time.LocalDate
import java.time.LocalDateTime

@Entity
@Table
data class Offence(
  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  val id: Long = 0,
  val code: String,
  val description: String? = null,
  val cjsTitle: String? = null,
  val revisionId: Int,
  val startDate: LocalDate,
  val endDate: LocalDate? = null,
  val category: Int? = null,
  val subCategory: Int? = null,
  val offenceType: String? = null,
  @Column(name = "ACTS_AND_SECTIONS")
  val legislation: String? = null,
  val parentOffenceId: Long? = null,
  @Enumerated(EnumType.STRING)
  val sdrsCache: SdrsCache,
  val changedDate: LocalDateTime,
  val createdDate: LocalDateTime = LocalDateTime.now(),
  val lastUpdatedDate: LocalDateTime = LocalDateTime.now(),
  val maxPeriodIsLife: Boolean? = false,
  val maxPeriodOfIndictmentYears: Int? = null,
  val maxPeriodOfIndictmentMonths: Int? = null,
  val maxPeriodOfIndictmentWeeks: Int? = null,
  val maxPeriodOfIndictmentDays: Int? = null,
  @ManyToOne
  @JoinColumns(
    JoinColumn(name = "category", referencedColumnName = "category", insertable = false, updatable = false),
    JoinColumn(name = "subCategory", referencedColumnName = "subCategory", insertable = false, updatable = false),
  )
  val homeOfficeCode: HomeOfficeCode? = null,
  @Enumerated(EnumType.ORDINAL)
  val custodialIndicator: CustodialIndicator? = null,
) {
  val statuteCode
    get() = code.substring(0, 4)
  val homeOfficeStatsCode: String?
    get() {
      if (category == null && subCategory == null) return null
      if (subCategory == null) return category.toString().padStart(3, '0') + "/"
      if (category == null) return "/" + subCategory.toString().padStart(2, '0')
      return category.toString().padStart(3, '0') + "/" + subCategory.toString().padStart(2, '0')
    }
  val derivedDescription: String
    get() = (cjsTitle ?: description)!!
  val statuteDescription: String
    get() = legislation?.takeUnless { it.isBlank() } ?: statuteCode
  val activeFlag: String
    get() = endDate?.takeUnless { it.isAfter(LocalDate.now()) }?.let { "N" } ?: "Y"
  val expiryDate: LocalDate?
    get() = endDate?.takeUnless { it.isAfter(LocalDate.now()) }?.let { LocalDate.now() }

  val parentCode: String?
    get() {
      if (code.length < 8) return null
      return code.substring(0, 7)
    }
  val severityRanking: String
    get() = if (category == null || category == 0) "99" else category.toString()

  fun isEncouragementOf(parent: Offence): Boolean = code == parent.code + "E"

  fun inheritHoCodeIfNull(parent: Offence): Offence = if (category == null && subCategory == null && parent.category != null && !isEncouragementOf(parent)) {
    copy(category = parent.category, subCategory = parent.subCategory)
  } else {
    this
  }
}
