/*
 * Licensed to The Apereo Foundation under one or more contributor license
 * agreements. See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.
 *
 * The Apereo Foundation licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License. You may obtain a copy of the License at:
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
*/
package org.unitime.timetable.gwt.server;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;
import java.util.TreeSet;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.unitime.localization.impl.Localization;
import org.unitime.timetable.defaults.ApplicationProperty;
import org.unitime.timetable.defaults.UserProperty;
import org.unitime.timetable.gwt.resources.GwtConstants;
import org.unitime.timetable.gwt.resources.GwtMessages;
import org.unitime.timetable.gwt.services.ReservationService;
import org.unitime.timetable.gwt.shared.PageAccessException;
import org.unitime.timetable.gwt.shared.ReservationException;
import org.unitime.timetable.gwt.shared.ReservationInterface;
import org.unitime.timetable.gwt.shared.ReservationInterface.ReservationFilterRpcRequest;
import org.unitime.timetable.interfaces.ExternalCourseOfferingReservationEditAction;
import org.unitime.timetable.model.AcademicArea;
import org.unitime.timetable.model.AcademicClassification;
import org.unitime.timetable.model.ChangeLog;
import org.unitime.timetable.model.Class_;
import org.unitime.timetable.model.CourseOffering;
import org.unitime.timetable.model.CourseReservation;
import org.unitime.timetable.model.Curriculum;
import org.unitime.timetable.model.CurriculumClassification;
import org.unitime.timetable.model.CurriculumCourse;
import org.unitime.timetable.model.CurriculumOverrideReservation;
import org.unitime.timetable.model.CurriculumProjectionRule;
import org.unitime.timetable.model.CurriculumReservation;
import org.unitime.timetable.model.GroupOverrideReservation;
import org.unitime.timetable.model.IndividualOverrideReservation;
import org.unitime.timetable.model.IndividualReservation;
import org.unitime.timetable.model.InstrOfferingConfig;
import org.unitime.timetable.model.InstructionalOffering;
import org.unitime.timetable.model.LearningCommunityReservation;
import org.unitime.timetable.model.Location;
import org.unitime.timetable.model.OverrideReservation;
import org.unitime.timetable.model.PosMajor;
import org.unitime.timetable.model.PosMajorConcentration;
import org.unitime.timetable.model.PosMinor;
import org.unitime.timetable.model.Reservation;
import org.unitime.timetable.model.SchedulingSubpart;
import org.unitime.timetable.model.Student;
import org.unitime.timetable.model.StudentGroup;
import org.unitime.timetable.model.StudentGroupReservation;
import org.unitime.timetable.model.StudentSectioningQueue;
import org.unitime.timetable.model.comparators.ClassComparator;
import org.unitime.timetable.model.comparators.InstrOfferingConfigComparator;
import org.unitime.timetable.model.comparators.SchedulingSubpartComparator;
import org.unitime.timetable.model.dao.AcademicAreaDAO;
import org.unitime.timetable.model.dao.AcademicClassificationDAO;
import org.unitime.timetable.model.dao.Class_DAO;
import org.unitime.timetable.model.dao.CourseOfferingDAO;
import org.unitime.timetable.model.dao.CurriculumDAO;
import org.unitime.timetable.model.dao.InstrOfferingConfigDAO;
import org.unitime.timetable.model.dao.InstructionalOfferingDAO;
import org.unitime.timetable.model.dao.PosMajorConcentrationDAO;
import org.unitime.timetable.model.dao.PosMajorDAO;
import org.unitime.timetable.model.dao.PosMinorDAO;
import org.unitime.timetable.model.dao.ReservationDAO;
import org.unitime.timetable.model.dao.StudentGroupDAO;
import org.unitime.timetable.security.SessionContext;
import org.unitime.timetable.security.UserContext;
import org.unitime.timetable.security.permissions.Permission;
import org.unitime.timetable.security.rights.Right;
import org.unitime.timetable.server.reservation.ReservationFilterBackend;
import org.unitime.timetable.solver.ClassAssignmentProxy;
import org.unitime.timetable.solver.ClassAssignmentProxy.AssignmentInfo;
import org.unitime.timetable.solver.service.AssignmentService;
import org.unitime.timetable.util.Constants;

/**
 * @author Tomas Muller
 */
@Service("reservation.gwt")
public class ReservationServlet implements ReservationService {
	protected static GwtConstants CONSTANTS = Localization.create(GwtConstants.class);
	protected static final GwtMessages MESSAGES = Localization.create(GwtMessages.class);
	private static Log sLog = LogFactory.getLog(ReservationServlet.class);

	private @Autowired SessionContext sessionContext;
	private SessionContext getSessionContext() { return sessionContext; }
	
	@Autowired Permission<InstructionalOffering> permissionOfferingLockNeeded;
	
	@Autowired Permission<InstructionalOffering> permissionOfferingLockNeededOnlyWhenWaitListing;
	
	@Autowired AssignmentService<ClassAssignmentProxy> classAssignmentService;

	@Override
	@PreAuthorize("checkPermission('Reservations')")
	public List<ReservationInterface.Area> getAreas() throws ReservationException, PageAccessException {
		try {
			List<ReservationInterface.Area> results = new ArrayList<ReservationInterface.Area>();
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			try {
				List<ReservationInterface.IdName> classifications = new ArrayList<ReservationInterface.IdName>();
				for (AcademicClassification classification: hibSession.createQuery(
						"select c from AcademicClassification c where c.session.uniqueId = :sessionId order by c.code, c.name", AcademicClassification.class)
						.setParameter("sessionId", getAcademicSessionId()).setCacheable(true).list()) {
					ReservationInterface.IdName clasf = new ReservationInterface.IdName();
					clasf.setId(classification.getUniqueId());
					clasf.setName(Constants.curriculaToInitialCase(classification.getName()));
					clasf.setAbbv(classification.getCode());
					classifications.add(clasf);
				}
				for (AcademicArea area: hibSession.createQuery(
						"select a from AcademicArea a where a.session.uniqueId = :sessionId order by a.academicAreaAbbreviation, a.title", AcademicArea.class)
						.setParameter("sessionId", getAcademicSessionId()).setCacheable(true).list()) {
					ReservationInterface.Area curriculum = new ReservationInterface.Area();
					curriculum.setAbbv(area.getAcademicAreaAbbreviation());
					curriculum.setId(area.getUniqueId());
					curriculum.setName(Constants.curriculaToInitialCase(area.getTitle()));
					for (PosMajor major: area.getPosMajors()) {
						ReservationInterface.IdName mj = new ReservationInterface.IdName();
						mj.setId(major.getUniqueId());
						mj.setAbbv(major.getCode());
						mj.setName(Constants.curriculaToInitialCase(major.getName()));
						mj.setParentId(area.getUniqueId());
						curriculum.getMajors().add(mj);
						for (PosMajorConcentration conc: major.getConcentrations()) {
							ReservationInterface.IdName cc = new ReservationInterface.IdName();
							cc.setId(conc.getUniqueId());
							cc.setAbbv(conc.getCode());
							cc.setName(Constants.curriculaToInitialCase(conc.getName()));
							cc.setParentId(conc.getMajor().getUniqueId());
							curriculum.getConcentrations().add(cc);
						}
					}
					for (PosMinor minor: area.getPosMinors()) {
						ReservationInterface.IdName mj = new ReservationInterface.IdName();
						mj.setId(minor.getUniqueId());
						mj.setAbbv(minor.getCode());
						mj.setName(Constants.curriculaToInitialCase(minor.getName()));
						mj.setParentId(area.getUniqueId());
						curriculum.getMinors().add(mj);
					}
					Collections.sort(curriculum.getMajors());
					Collections.sort(curriculum.getMinors());
					Collections.sort(curriculum.getConcentrations());
					curriculum.getClassifications().addAll(classifications);
					results.add(curriculum);
				}
			} finally {
				hibSession.close();
			}
			return results;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	private ReservationInterface.Offering convert(InstructionalOffering io, Long courseId, org.hibernate.Session hibSession) throws ReservationException, PageAccessException {
		return convert(io, courseId, hibSession, permissionOfferingLockNeededOnlyWhenWaitListing, sessionContext, classAssignmentService.getAssignment());
	}
	
	public static ReservationInterface.Offering convert(InstructionalOffering io, Long courseId, org.hibernate.Session hibSession,
			Permission<InstructionalOffering> permissionOfferingLockNeededOnlyWhenWaitListing, SessionContext sessionContext,
			ClassAssignmentProxy assignments
			) {
		CourseOffering cc = io.getControllingCourseOffering();
		if (courseId != null) {
			for (CourseOffering co: io.getCourseOfferings())
				if (courseId.equals(co.getUniqueId()))  { cc = co; break; }
		}
		ReservationInterface.Offering offering = new ReservationInterface.Offering();
		offering.setAbbv(io.getCourseName());
		offering.setName(io.getControllingCourseOffering().getTitle());
		offering.setId(io.getUniqueId());
		offering.setOffered(!io.isNotOffered());
		offering.setUnlockNeeded(permissionOfferingLockNeededOnlyWhenWaitListing != null && permissionOfferingLockNeededOnlyWhenWaitListing.check(sessionContext.getUser(), io));
		for (CourseOffering co: io.getCourseOfferings()) {
			ReservationInterface.Course course = new ReservationInterface.Course();
			course.setId(co.getUniqueId());
			course.setAbbv(co.getCourseName());
			course.setName(co.getTitle());
			course.setControl(co.isIsControl());
			course.setLimit(co.getReservation());
			offering.getCourses().add(course);
		}
		String nameFormat = UserProperty.NameFormat.get(sessionContext.getUser());
		List<InstrOfferingConfig> configs = new ArrayList<InstrOfferingConfig>(io.getInstrOfferingConfigs());
		Collections.sort(configs, new InstrOfferingConfigComparator(null));
		for (InstrOfferingConfig ioc: configs) {
			ReservationInterface.Config config = new ReservationInterface.Config();
			config.setId(ioc.getUniqueId());
			config.setName(ioc.getName());
			config.setAbbv(ioc.getName());
			config.setLimit(ioc.isUnlimitedEnrollment() ? null : ioc.getLimit());
			config.setInstructionalMethod(ioc.getInstructionalMethod() == null ? null : ioc.getInstructionalMethod().getLabel());
			offering.getConfigs().add(config);
			TreeSet<SchedulingSubpart> subparts = new TreeSet<SchedulingSubpart>(new SchedulingSubpartComparator());
			subparts.addAll(ioc.getSchedulingSubparts());
			for (SchedulingSubpart ss: subparts) {
				ReservationInterface.Subpart subpart = new ReservationInterface.Subpart();
				subpart.setId(ss.getUniqueId());
				String suffix = ss.getSchedulingSubpartSuffix(hibSession);
				subpart.setAbbv(ss.getItypeDesc() + (suffix == null || suffix.isEmpty() ? "" : " " + suffix));
				subpart.setName(ss.getSchedulingSubpartLabel());
				subpart.setConfig(config);
				config.getSubparts().add(subpart);
				if (ss.getParentSubpart() != null)
					subpart.setParentId(ss.getParentSubpart().getUniqueId());
				List<Class_> classes = new ArrayList<Class_>(ss.getClasses());
				Collections.sort(classes, new ClassComparator(ClassComparator.COMPARE_BY_HIERARCHY));
				for (Class_ c: classes) {
					ReservationInterface.Clazz clazz = new ReservationInterface.Clazz();
					clazz.setId(c.getUniqueId());
					clazz.setAbbv(ss.getItypeDesc() + " " + c.getSectionNumberString(hibSession));
					clazz.setName(c.getClassLabel(hibSession));
					clazz.setExternalId(c.getClassSuffix(cc));
					if (assignments != null) {
						try {
							AssignmentInfo a = assignments.getAssignment(c);
							if (a != null) {
								clazz.setDate(a.getDatePattern() != null ? a.getDatePattern().getName() : null);
								if (a.getTimeLocation() != null) {
					    			String time = "";
					    			Integer firstDay = ApplicationProperty.TimePatternFirstDayOfWeek.intValue();
					    			for (int i = 0; i < CONSTANTS.shortDays().length; i++) {
					    				int idx = (firstDay == null ? i : (i + firstDay) % 7);
					    				if ((Constants.DAY_CODES[idx] & a.getTimeLocation().getDayCode()) != 0) time += CONSTANTS.shortDays()[idx];
					    			}
					   				time += " " + a.getTimeLocation().getStartTimeHeader(CONSTANTS.useAmPm()) +
					   						" - " + a.getTimeLocation().getEndTimeHeader(CONSTANTS.useAmPm());
					   				clazz.setTime(time);
								}
								if (a.getRooms() != null && !a.getRooms().isEmpty()) {
									String rooms = "";
									for (Location location: a.getRooms()) {
										if (!rooms.isEmpty()) rooms += ", ";
										rooms += location.getLabel();
									}
									clazz.setRoom(rooms);
								}
							}
						} catch (Exception e) {}
					}
					clazz.setInstructor(c.instructorText(nameFormat, "; "));
					if (c.isCancelled() != null)
						clazz.setCancelled(c.isCancelled());
					subpart.getClasses().add(clazz);
					clazz.setSubpart(subpart);
					clazz.setLimit(c.getClassLimit());
					if (c.getParentClass() != null)
						clazz.setParentId(c.getParentClass().getUniqueId());
					clazz.setEnrollment(c.getEnrollment());
				}
			}
		}
		return offering;
	}

	@Override
	@PreAuthorize("checkPermission('Reservations')")
	public ReservationInterface.Offering getOffering(Long offeringId, Long courseId) throws ReservationException, PageAccessException {
		try {
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			try {
				InstructionalOffering io = InstructionalOfferingDAO.getInstance().get(offeringId, hibSession);
				if (io == null) { throw new ReservationException(MESSAGES.errorOfferingDoesNotExist(offeringId == null ? "null" : offeringId.toString())); }
				return convert(io, courseId, hibSession);
			} finally {
				hibSession.close();
			}
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	private CourseOffering getCourse(org.hibernate.Session hibSession, String courseName) {
		for (CourseOffering co: hibSession.createQuery(
				"select c from CourseOffering c where " +
				"c.subjectArea.session.uniqueId = :sessionId and " +
				"lower(c.subjectArea.subjectAreaAbbreviation || ' ' || c.courseNbr) = :course", CourseOffering.class)
				.setParameter("course", courseName.toLowerCase())
				.setParameter("sessionId", getAcademicSessionId())
				.setCacheable(true).setMaxResults(1).list()) {
			return co;
		}
		return null;
	}
	
	@PreAuthorize("checkPermission('Reservations')")
	public ReservationInterface.Offering getOfferingByCourseName(String courseName) throws ReservationException, PageAccessException{
		try {
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			try {
				CourseOffering co = getCourse(hibSession, courseName);
				if (co == null) { throw new ReservationException(MESSAGES.errorCourseDoesNotExist(courseName)); }
				return convert(co.getInstructionalOffering(), co.getUniqueId(), hibSession);
			} finally {
				hibSession.close();
			}
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	private Hashtable<String,HashMap<String, Float>> getRules(org.hibernate.Session hibSession, Long acadAreaId) {
		Hashtable<String,HashMap<String, Float>> clasf2major2proj = new Hashtable<String, HashMap<String,Float>>();
		for (CurriculumProjectionRule rule: hibSession.createQuery(
				"select r from CurriculumProjectionRule r where r.academicArea.uniqueId=:acadAreaId", CurriculumProjectionRule.class)
				.setParameter("acadAreaId", acadAreaId).setCacheable(true).list()) {
			String majorCode = (rule.getMajor() == null ? "" : rule.getMajor().getCode());
			String clasfCode = rule.getAcademicClassification().getCode();
			Float projection = rule.getProjection();
			HashMap<String, Float> major2proj = clasf2major2proj.get(clasfCode);
			if (major2proj == null) {
				major2proj = new HashMap<String, Float>();
				clasf2major2proj.put(clasfCode, major2proj);
			}
			major2proj.put(majorCode, projection);
		}
		return clasf2major2proj;
	}
	
	private float getProjection(Hashtable<String,HashMap<String, Float>> clasf2major2proj, String majorCode, String clasfCode) {
		if (clasf2major2proj == null || clasf2major2proj.isEmpty()) return 1.0f;
		HashMap<String, Float> major2proj = clasf2major2proj.get(clasfCode);
		if (major2proj == null) return 1.0f;
		Float projection = major2proj.get(majorCode);
		if (projection == null)
			projection = major2proj.get("");
		return (projection == null ? 1.0f : projection);
	}
	
	private ReservationInterface convert(Reservation reservation, String nameFormat, org.hibernate.Session hibSession) {
		ReservationInterface r = null;
		CourseOffering co = reservation.getInstructionalOffering().getControllingCourseOffering();
		if (reservation instanceof CourseReservation) {
			co = ((CourseReservation) reservation).getCourse();
			ReservationInterface.Course course = new ReservationInterface.Course();
			course.setId(co.getUniqueId());
			course.setAbbv(co.getCourseName());
			course.setControl(co.isIsControl());
			course.setName(co.getTitle());
			course.setLimit(co.getReservation());
			r = new ReservationInterface.CourseReservation();
			((ReservationInterface.CourseReservation) r).setCourse(course);
			r.setLastLike(co.getDemand());
			r.setEnrollment(co.getEnrollment());
			r.setProjection(co.getProjectedDemand());
		} else if (reservation instanceof IndividualReservation) {
			r = new ReservationInterface.IndividualReservation();
			if (reservation instanceof OverrideReservation) {
				r = new ReservationInterface.OverrideReservation(((OverrideReservation)reservation).getOverrideType());
			}
			String sId = "";
			for (Student student: ((IndividualReservation) reservation).getStudents()) {
				ReservationInterface.IdName s = new ReservationInterface.IdName();
				s.setId(student.getUniqueId());
				s.setAbbv(student.getExternalUniqueId());
				s.setName(student.getName(nameFormat));
				((ReservationInterface.IndividualReservation) r).getStudents().add(s);
				sId += (sId.isEmpty() ? "" : ",") + student.getUniqueId();
			}
			Collections.sort(((ReservationInterface.IndividualReservation) r).getStudents(), new Comparator<ReservationInterface.IdName>() {
				@Override
				public int compare(ReservationInterface.IdName s1, ReservationInterface.IdName s2) {
					int cmp = s1.getName().compareTo(s2.getName());
					if (cmp != 0) return cmp;
					return s1.getAbbv().compareTo(s2.getAbbv());
				}
			});
			if (!sId.isEmpty()) {
				Number enrollment = hibSession.createQuery(
						"select count(distinct e.student) " +
						"from StudentClassEnrollment e where " +
						"e.courseOffering.instructionalOffering.uniqueId = :offeringId " +
						"and e.student.uniqueId in (" + sId + ")", Number.class)
						.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId()).setCacheable(true).uniqueResult();
				if (enrollment.intValue() > 0)
					r.setEnrollment(enrollment.intValue());
			}
		} else if (reservation instanceof CurriculumReservation) {
			CurriculumReservation cr = (CurriculumReservation) reservation;
			r = new ReservationInterface.CurriculumReservation();
			ReservationInterface.Areas curriculum = new ReservationInterface.Areas();
			String aaCodes = "";
			String aaIds = "";
			for (AcademicArea area: cr.getAreas()) {
				ReservationInterface.IdName aa = new ReservationInterface.IdName();
				aa.setId(area.getUniqueId());
				aa.setAbbv(area.getAcademicAreaAbbreviation());
				aa.setName(Constants.curriculaToInitialCase(area.getTitle()));
				curriculum.getAreas().add(aa);
				aaCodes += (aaCodes.isEmpty() ? "" : ",") + "'" + area.getAcademicAreaAbbreviation() + "'";
				aaIds += (aaIds.isEmpty() ? "" : ",") + area.getUniqueId();
			}
			String cfCodes = "";
			String cfIds = "";
			for (AcademicClassification classification: cr.getClassifications()) {
				ReservationInterface.IdName clasf = new ReservationInterface.IdName();
				clasf.setId(classification.getUniqueId());
				clasf.setName(Constants.curriculaToInitialCase(classification.getName()));
				clasf.setAbbv(classification.getCode());
				curriculum.getClassifications().add(clasf);
				cfCodes += (cfCodes.isEmpty() ? "" : ",") + "'" + classification.getCode() + "'";
				cfIds += (cfIds.isEmpty() ? "" : ",") + classification.getUniqueId();
			}
			String mjCodes = "";
			String mjIds = "";
			for (PosMajor major: cr.getMajors()) {
				ReservationInterface.IdName mj = new ReservationInterface.IdName();
				mj.setId(major.getUniqueId());
				mj.setAbbv(major.getCode());
				mj.setName(Constants.curriculaToInitialCase(major.getName()));
				for (AcademicArea aa: major.getAcademicAreas())
					if (cr.getAreas().contains(aa)) {
						mj.setParentId(aa.getUniqueId());
						break;
					}
				curriculum.getMajors().add(mj);
				mjCodes += (mjCodes.isEmpty() ? "" : ",") + "'" + major.getCode() + "'";
				mjIds += (mjIds.isEmpty() ? "" : ",") + major.getUniqueId();
			}
			String mnCodes = "";
			String mnIds = "";
			for (PosMinor minor: cr.getMinors()) {
				ReservationInterface.IdName mn = new ReservationInterface.IdName();
				mn.setId(minor.getUniqueId());
				mn.setAbbv(minor.getCode());
				mn.setName(Constants.curriculaToInitialCase(minor.getName()));
				for (AcademicArea aa: minor.getAcademicAreas())
					if (cr.getAreas().contains(aa)) {
						mn.setParentId(aa.getUniqueId());
						break;
					}
				curriculum.getMinors().add(mn);
				mnCodes += (mnCodes.isEmpty() ? "" : ",") + "'" + minor.getCode() + "'";
				mnIds += (mnIds.isEmpty() ? "" : ",") + minor.getUniqueId();
			}
			String ccCodes = "";
			String ccIds = "";
			for (PosMajorConcentration conc: cr.getConcentrations()) {
				ReservationInterface.IdName cc = new ReservationInterface.IdName();
				cc.setId(conc.getUniqueId());
				cc.setAbbv(conc.getCode());
				cc.setName(Constants.curriculaToInitialCase(conc.getName()));
				cc.setParentId(conc.getMajor().getUniqueId());
				curriculum.getConcentrations().add(cc);
				ccCodes += (ccCodes.isEmpty() ? "" : ",") + "'" + conc.getCode() + "'";
				ccIds += (ccIds.isEmpty() ? "" : ",") + conc.getUniqueId();
			}
			if (curriculum.getAreas().size() > 1)
				Collections.sort(curriculum.getAreas(), new Comparator<ReservationInterface.IdName>() {
					@Override
					public int compare(ReservationInterface.IdName s1, ReservationInterface.IdName s2) {
						int cmp = s1.getAbbv().compareTo(s2.getAbbv());
						if (cmp != 0) return cmp;
						cmp = s1.getName().compareTo(s2.getName());
						if (cmp != 0) return cmp;
						return s1.getId().compareTo(s2.getId());
					}
				});
			Collections.sort(curriculum.getMajors(), new Comparator<ReservationInterface.IdName>() {
				@Override
				public int compare(ReservationInterface.IdName s1, ReservationInterface.IdName s2) {
					int cmp = s1.getAbbv().compareTo(s2.getAbbv());
					if (cmp != 0) return cmp;
					cmp = s1.getName().compareTo(s2.getName());
					if (cmp != 0) return cmp;
					return s1.getId().compareTo(s2.getId());
				}
			});
			Collections.sort(curriculum.getClassifications(), new Comparator<ReservationInterface.IdName>() {
				@Override
				public int compare(ReservationInterface.IdName s1, ReservationInterface.IdName s2) {
					int cmp = s1.getAbbv().compareTo(s2.getAbbv());
					if (cmp != 0) return cmp;
					cmp = s1.getName().compareTo(s2.getName());
					if (cmp != 0) return cmp;
					return s1.getId().compareTo(s2.getId());
				}
			});
			Collections.sort(curriculum.getMinors(), new Comparator<ReservationInterface.IdName>() {
				@Override
				public int compare(ReservationInterface.IdName s1, ReservationInterface.IdName s2) {
					int cmp = s1.getAbbv().compareTo(s2.getAbbv());
					if (cmp != 0) return cmp;
					cmp = s1.getName().compareTo(s2.getName());
					if (cmp != 0) return cmp;
					return s1.getId().compareTo(s2.getId());
				}
			});
			Collections.sort(curriculum.getConcentrations(), new Comparator<ReservationInterface.IdName>() {
				@Override
				public int compare(ReservationInterface.IdName s1, ReservationInterface.IdName s2) {
					int cmp = s1.getAbbv().compareTo(s2.getAbbv());
					if (cmp != 0) return cmp;
					cmp = s1.getName().compareTo(s2.getName());
					if (cmp != 0) return cmp;
					return s1.getId().compareTo(s2.getId());
				}
			});
			((ReservationInterface.CurriculumReservation) r).setCurriculum(curriculum);
			if (!mjIds.isEmpty() || mnIds.isEmpty()) {
				Number enrollment = hibSession.createQuery(
						"select count(distinct e.student) " +
						"from StudentClassEnrollment e inner join e.student.areaClasfMajors a inner join a.major m " +
								(ccIds.isEmpty() ? "" : "left outer join a.concentration c ") + "where " +
						"e.courseOffering.instructionalOffering.uniqueId = :offeringId " +
						(mjIds.isEmpty() ? "" : " and m.uniqueId in (" + mjIds + ")") +
						(cfIds.isEmpty() ? "" : " and a.academicClassification.uniqueId in (" + cfIds + ")") +
						(aaIds.isEmpty() ? "" : " and a.academicArea.uniqueId in (" + aaIds + ")") +
						(ccIds.isEmpty() ? "" : " and (c is null or c.uniqueId in (" + ccIds + "))"), Number.class)
						.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
						.setCacheable(true).uniqueResult();
				if (enrollment.intValue() > 0)
					r.setEnrollment(enrollment.intValue());
			}
			if (!mnIds.isEmpty()) {
				Number enrollment = hibSession.createQuery(
						"select count(distinct e.student) " +
						"from StudentClassEnrollment e inner join e.student.areaClasfMinors a inner join a.minor m where " +
						"e.courseOffering.instructionalOffering.uniqueId = :offeringId and m.uniqueId in (" + mnIds + ")" +
						(cfIds.isEmpty() ? "" : " and a.academicClassification.uniqueId in (" + cfIds + ")") +
						(aaIds.isEmpty() ? "" : " and a.academicArea.uniqueId in (" + aaIds + ")"), Number.class)
						.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
						.setCacheable(true).uniqueResult();
				if (enrollment.intValue() > 0)
					r.setEnrollment(enrollment.intValue() + (r.getEnrollment() == null ? 0 : r.getEnrollment().intValue()));
			}
			/*
			Number lastLike = hibSession.createQuery(
					"select count(distinct s) from " +
					"LastLikeCourseDemand x inner join x.student s inner join s.areaClasfMajors a inner join a.major m " +
					"inner join a.academicClassification f inner join a.academicArea r, CourseOffering co where " +
					"x.subjectArea.session.uniqueId = :sessionId and co.instructionalOffering.uniqueId = :offeringId and "+
					"co.subjectArea.uniqueId = x.subjectArea.uniqueId and " +
					"((x.coursePermId is not null and co.permId=x.coursePermId) or (x.coursePermId is null and co.courseNbr=x.courseNbr)) " +
					"and r.academicAreaAbbreviation = :areaAbbv" +
					(mjCodes.isEmpty() ? "" : " and m.code in (" + mjCodes + ")") +
					(cfCodes.isEmpty() ? "" : " and f.code in (" + cfCodes + ")"), Number.class)
					.setParameter("sessionId", getAcademicSessionId())
					.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
					.setParameter("areaAbbv", cr.getArea().getAcademicAreaAbbreviation()).uniqueResult();
			r.setLastLike(lastLike.intValue());
			*/
			float projection = 0f;
			int lastLike = 0;
			if (!mjIds.isEmpty() || mnIds.isEmpty())
				for (AcademicArea area: cr.getAreas()) {
					Hashtable<String,HashMap<String, Float>> rules = getRules(hibSession, area.getUniqueId());
					for (Object[] o: hibSession.createQuery(
							"select count(distinct x.student), m.code, f.code from " +
							"LastLikeCourseDemand x inner join x.student s inner join s.areaClasfMajors a inner join a.major m " +
							"inner join a.academicClassification f inner join a.academicArea r"+
							(ccIds.isEmpty() ? "" : " left outer join a.concentration c") +
							", CourseOffering co left outer join co.demandOffering do where " +
							"x.subjectArea.session.uniqueId = :sessionId and co.instructionalOffering.uniqueId = :offeringId and "+
							"((co.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and co.permId=x.coursePermId) or (x.coursePermId is null and co.courseNbr=x.courseNbr))) or "+
							"(do is not null and do.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and do.permId=x.coursePermId) or (x.coursePermId is null and do.courseNbr=x.courseNbr))))"+
							"and r.academicAreaAbbreviation = :areaAbbv" +
							(mjCodes.isEmpty() ? "" : " and m.code in (" + mjCodes + ")") +
							(cfCodes.isEmpty() ? "" : " and f.code in (" + cfCodes + ")") +
							(ccIds.isEmpty() ? "" : " and (c is null or c.uniqueId in (" + ccIds + "))") +
							" group by m.code, f.code", Object[].class)
							.setParameter("sessionId", getAcademicSessionId())
							.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
							.setParameter("areaAbbv", area.getAcademicAreaAbbreviation()).setCacheable(true).list()) {
						int nrStudents = ((Number)o[0]).intValue();
						lastLike += Math.round(nrStudents);
						projection += getProjection(rules, (String)o[1], (String)o[2]) * nrStudents;
					}
				}
			if (!mnIds.isEmpty())
				for (AcademicArea area: cr.getAreas()) {
					for (Object[] o: hibSession.createQuery(
							"select count(distinct x.student), m.code, f.code from " +
							"LastLikeCourseDemand x inner join x.student s inner join s.areaClasfMinors a inner join a.minor m " +
							"inner join a.academicClassification f inner join a.academicArea r, CourseOffering co left outer join co.demandOffering do where " +
							"x.subjectArea.session.uniqueId = :sessionId and co.instructionalOffering.uniqueId = :offeringId and "+
							"((co.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and co.permId=x.coursePermId) or (x.coursePermId is null and co.courseNbr=x.courseNbr))) or "+
							"(do is not null and do.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and do.permId=x.coursePermId) or (x.coursePermId is null and do.courseNbr=x.courseNbr))))"+
							"and r.academicAreaAbbreviation = :areaAbbv" +
							(mnCodes.isEmpty() ? "" : " and m.code in (" + mnCodes + ")") +
							(cfCodes.isEmpty() ? "" : " and f.code in (" + cfCodes + ")") +
							" group by m.code, f.code", Object[].class)
							.setParameter("sessionId", getAcademicSessionId())
							.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
							.setParameter("areaAbbv", area.getAcademicAreaAbbreviation()).setCacheable(true).list()) {
						int nrStudents = ((Number)o[0]).intValue();
						lastLike += nrStudents;
						projection += nrStudents;
					}
				}
			if (lastLike > 0) {
				r.setLastLike(lastLike);
				r.setProjection(Math.round(projection));
			}
			
		} else if (reservation instanceof LearningCommunityReservation) {
			r = new ReservationInterface.LCReservation();
			
			StudentGroup sg = ((LearningCommunityReservation) reservation).getGroup();
			ReservationInterface.IdName group = new ReservationInterface.IdName();
			group.setId(sg.getUniqueId());
			group.setName(sg.getGroupName());
			group.setAbbv(sg.getGroupAbbreviation());
			group.setLimit(sg.getStudents().size());
			((ReservationInterface.LCReservation) r).setGroup(group);
			
			co = ((LearningCommunityReservation) reservation).getCourse();
			ReservationInterface.Course course = new ReservationInterface.Course();
			course.setId(co.getUniqueId());
			course.setAbbv(co.getCourseName());
			course.setControl(co.isIsControl());
			course.setName(co.getTitle());
			course.setLimit(co.getReservation());
			((ReservationInterface.LCReservation) r).setCourse(course);
			
			Number enrollment = hibSession.createQuery(
					"select count(distinct e.student) " +
					"from StudentClassEnrollment e inner join e.student.groups g where " +
					"e.courseOffering.uniqueId = :courseId " +
					"and g.uniqueId = :groupId", Number.class)
					.setParameter("courseId", course.getId())
					.setParameter("groupId", sg.getUniqueId()).setCacheable(true).uniqueResult();
			if (enrollment.intValue() > 0)
				r.setEnrollment(enrollment.intValue());

			Number lastLike = hibSession.createQuery(
					"select count(distinct x.student) from " +
					"LastLikeCourseDemand x inner join x.student s inner join s.groups g, CourseOffering co left outer join co.demandOffering do where " +
					"x.subjectArea.session.uniqueId = :sessionId and co.uniqueId = :courseId and "+
					"((co.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and co.permId=x.coursePermId) or (x.coursePermId is null and co.courseNbr=x.courseNbr))) or "+
					"(do is not null and do.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and do.permId=x.coursePermId) or (x.coursePermId is null and do.courseNbr=x.courseNbr))))"+
					"and g.groupAbbreviation = :groupAbbv", Number.class)
					.setParameter("sessionId", getAcademicSessionId())
					.setParameter("courseId", course.getId())
					.setParameter("groupAbbv", sg.getGroupAbbreviation())
					.setCacheable(true).uniqueResult();
			if (lastLike.intValue() > 0)
				r.setLastLike(lastLike.intValue());
		} else if (reservation instanceof StudentGroupReservation) {
			r = new ReservationInterface.GroupReservation();
			StudentGroup sg = ((StudentGroupReservation) reservation).getGroup();
			ReservationInterface.IdName group = new ReservationInterface.IdName();
			group.setId(sg.getUniqueId());
			group.setName(sg.getGroupName());
			group.setAbbv(sg.getGroupAbbreviation());
			group.setLimit(sg.getStudents().size());
			((ReservationInterface.GroupReservation) r).setGroup(group);
			Number enrollment = hibSession.createQuery(
					"select count(distinct e.student) " +
					"from StudentClassEnrollment e inner join e.student.groups g where " +
					"e.courseOffering.instructionalOffering.uniqueId = :offeringId " +
					"and g.uniqueId = :groupId", Number.class)
					.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
					.setParameter("groupId", sg.getUniqueId()).setCacheable(true).uniqueResult();
			if (enrollment.intValue() > 0)
				r.setEnrollment(enrollment.intValue());
			Number lastLike = hibSession.createQuery(
					"select count(distinct x.student) from " +
					"LastLikeCourseDemand x inner join x.student s inner join s.groups g, CourseOffering co left outer join co.demandOffering do where " +
					"x.subjectArea.session.uniqueId = :sessionId and co.instructionalOffering.uniqueId = :offeringId and "+
					"((co.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and co.permId=x.coursePermId) or (x.coursePermId is null and co.courseNbr=x.courseNbr))) or "+
					"(do is not null and do.subjectArea.uniqueId = x.subjectArea.uniqueId and ((x.coursePermId is not null and do.permId=x.coursePermId) or (x.coursePermId is null and do.courseNbr=x.courseNbr))))"+
					"and g.groupAbbreviation = :groupAbbv", Number.class)
					.setParameter("sessionId", getAcademicSessionId())
					.setParameter("offeringId", reservation.getInstructionalOffering().getUniqueId())
					.setParameter("groupAbbv", sg.getGroupAbbreviation())
					.setCacheable(true).uniqueResult();
			if (lastLike.intValue() > 0)
				r.setLastLike(lastLike.intValue());
		} else {
			throw new ReservationException(MESSAGES.errorUnknownReservationType(reservation.getClass().getName()));
		}
		ReservationInterface.Offering offering = new ReservationInterface.Offering();
		offering.setAbbv(co.getCourseName());
		offering.setName(co.getTitle());
		offering.setId(reservation.getInstructionalOffering().getUniqueId());
		offering.setOffered(!reservation.getInstructionalOffering().isNotOffered());
		offering.setUnlockNeeded(permissionOfferingLockNeededOnlyWhenWaitListing != null && permissionOfferingLockNeededOnlyWhenWaitListing.check(sessionContext.getUser(), reservation.getInstructionalOffering()));
		r.setOffering(offering);
		boolean showClassSuffixes = ApplicationProperty.ReservationsShowClassSufix.isTrue();
		for (CourseOffering cx: reservation.getInstructionalOffering().getCourseOfferings()) {
			ReservationInterface.Course course = new ReservationInterface.Course();
			course.setId(cx.getUniqueId());
			course.setAbbv(cx.getCourseName());
			course.setName(cx.getTitle());
			course.setControl(cx.isIsControl());
			course.setLimit(cx.getReservation());
			offering.getCourses().add(course);
		}
		List<InstrOfferingConfig> configs = new ArrayList<InstrOfferingConfig>(reservation.getConfigurations());
		Collections.sort(configs, new InstrOfferingConfigComparator(null));
		for (InstrOfferingConfig ioc: configs) {
			ReservationInterface.Config config = new ReservationInterface.Config();
			config.setId(ioc.getUniqueId());
			config.setName(ioc.getName());
			config.setAbbv(ioc.getName());
			config.setLimit(ioc.isUnlimitedEnrollment() ? null : ioc.getLimit());
			r.getConfigs().add(config);
		}
		List<Class_> classes = new ArrayList<Class_>(reservation.getClasses());
		Collections.sort(classes, new ClassComparator(ClassComparator.COMPARE_BY_HIERARCHY));
		for (Class_ c: classes) {
			ReservationInterface.Clazz clazz = new ReservationInterface.Clazz();
			clazz.setId(c.getUniqueId());
			clazz.setAbbv(c.getSchedulingSubpart().getItypeDesc() + " " + c.getSectionNumberString(hibSession));
			clazz.setName(c.getClassLabel(co, showClassSuffixes));
			clazz.setLimit(c.getClassLimit());
			r.getClasses().add(clazz);
		}
		r.setStartDate(reservation.getStartDate());
		r.setExpirationDate(reservation.getExpirationDate());
		r.setExpired(reservation.isExpired());
		r.setLimit(reservation.getLimit());
		r.setInclusive(reservation.getInclusive());
		r.setId(reservation.getUniqueId());
		r.setOverride(reservation instanceof IndividualOverrideReservation || reservation instanceof GroupOverrideReservation || reservation instanceof CurriculumOverrideReservation);
		r.setAllowOverlaps(reservation.isAllowOverlap());
		r.setMustBeUsed(reservation.isMustBeUsed());
		r.setAlwaysExpired(reservation.isAlwaysExpired());
		r.setOverLimit(reservation.isCanAssignOverLimit());
		return r;
	}

	@Override
	@PreAuthorize("checkPermission('Reservations')")
	public List<ReservationInterface> getReservations(Long offeringId) throws ReservationException, PageAccessException {
		try {
			List<ReservationInterface> results = new ArrayList<ReservationInterface>();
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			String nameFormat = UserProperty.NameFormat.get(getSessionContext().getUser());
			try {
				for (Reservation reservation: hibSession.createQuery(
						"select r from Reservation r where r.instructionalOffering.uniqueId = :offeringId", Reservation.class)
						.setParameter("offeringId", offeringId).setCacheable(true).list()) {
					ReservationInterface r = convert(reservation, nameFormat, hibSession);
					r.setEditable(getSessionContext().hasPermission(reservation, Right.ReservationEdit));
					if (r instanceof ReservationInterface.OverrideReservation) {
						ReservationInterface.OverrideReservation o = (ReservationInterface.OverrideReservation)r;
						if (o.getType() != null && !o.getType().isEditable())
							r.setEditable(false);
					}
					results.add(r);
				}				
			} finally {
				hibSession.close();
			}
			Collections.sort(results);
			return results;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}

	@Override
	@PreAuthorize("checkPermission('Reservations')")
	public List<ReservationInterface.IdName> getStudentGroups() throws ReservationException, PageAccessException {
		try {
			List<ReservationInterface.IdName> results = new ArrayList<ReservationInterface.IdName>();
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			try {
				for (StudentGroup sg: hibSession.createQuery(
						"select g from StudentGroup g where g.session.uniqueId = :sessionId order by g.groupName", StudentGroup.class)
						.setParameter("sessionId", getAcademicSessionId()).setCacheable(true).list()) {
					ReservationInterface.IdName group = new ReservationInterface.IdName();
					group.setId(sg.getUniqueId());
					group.setName(sg.getGroupAbbreviation());
					group.setAbbv(sg.getGroupName());
					group.setLimit(sg.getStudents().size());
					results.add(group);
				}
			} finally {
				hibSession.close();
			}
			return results;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}

	@Override
	@PreAuthorize("checkPermission(#reservationId, 'Reservation', 'ReservationEdit')")
	public ReservationInterface getReservation(Long reservationId) throws ReservationException, PageAccessException {
		try {
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			ReservationInterface r;
			try {
				Reservation reservation = ReservationDAO.getInstance().get(reservationId, hibSession);
				if (reservation == null)
					throw new ReservationException("Reservation not found.");
				r = convert(reservation, UserProperty.NameFormat.get(getSessionContext().getUser()), hibSession);
				r.setEditable(getSessionContext().hasPermission(reservation, Right.ReservationEdit));
				if (r instanceof ReservationInterface.OverrideReservation) {
					ReservationInterface.OverrideReservation o = (ReservationInterface.OverrideReservation)r;
					if (o.getType() != null && !o.getType().isEditable())
						r.setEditable(false);
				}
			} finally {
				hibSession.close();
			}
			return r;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	@Override
	@PreAuthorize("(#reservation.id != null and checkPermission(#reservation.id, 'Reservation', 'ReservationEdit')) or (#reservation.id == null and checkPermission(#reservation.offering.id, 'InstructionalOffering', 'ReservationOffering') and checkPermission('ReservationAdd'))")
	public Long save(ReservationInterface reservation) throws ReservationException, PageAccessException {
		try {
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			UserContext user = getSessionContext().getUser();
			try {
				InstructionalOffering offering = InstructionalOfferingDAO.getInstance().get(reservation.getOffering().getId(), hibSession);
				if (offering == null)
					throw new ReservationException(MESSAGES.errorOfferingDoesNotExist(reservation.getOffering().getName()));
				Reservation r = null;
				if (reservation.getId() != null) {
					r = ReservationDAO.getInstance().get(reservation.getId(), hibSession);
				}
				if (r == null) {
					if (reservation instanceof ReservationInterface.OverrideReservation) {
						r = new OverrideReservation();
						((OverrideReservation)r).setOverrideType(((ReservationInterface.OverrideReservation)reservation).getType());
					} else if (reservation instanceof ReservationInterface.IndividualReservation) {
						r = new IndividualReservation();
						if (reservation.isOverride())
							r = new IndividualOverrideReservation();
					} else if (reservation instanceof ReservationInterface.GroupReservation) {
						r = new StudentGroupReservation();
						if (reservation.isOverride())
							r = new GroupOverrideReservation();
					} else if (reservation instanceof ReservationInterface.CurriculumReservation) {
						r = new CurriculumReservation();
						if (reservation.isOverride())
							r = new CurriculumOverrideReservation();
					} else if (reservation instanceof ReservationInterface.CourseReservation)
						r = new CourseReservation();
					else if (reservation instanceof ReservationInterface.LCReservation)
						r = new LearningCommunityReservation();
					else
						throw new ReservationException(MESSAGES.errorUnknownReservationType(reservation.getClass().getName()));
				}
				r.setLimit(r instanceof IndividualReservation ? null : reservation.getLimit());
				r.setStartDate(reservation.getStartDate());
				r.setExpirationDate(reservation.getExpirationDate());
				r.setInstructionalOffering(offering);
				r.setInclusive(reservation.isInclusive());
				if (r instanceof IndividualOverrideReservation) {
					((IndividualOverrideReservation)r).setAllowOverlap(reservation.isAllowOverlaps());
					((IndividualOverrideReservation)r).setAlwaysExpired(reservation.isAlwaysExpired());
					((IndividualOverrideReservation)r).setCanAssignOverLimit(reservation.isOverLimit());
					((IndividualOverrideReservation)r).setMustBeUsed(reservation.isMustBeUsed());
				} else if (r instanceof GroupOverrideReservation) {
					((GroupOverrideReservation)r).setAllowOverlap(reservation.isAllowOverlaps());
					((GroupOverrideReservation)r).setAlwaysExpired(reservation.isAlwaysExpired());
					((GroupOverrideReservation)r).setCanAssignOverLimit(reservation.isOverLimit());
					((GroupOverrideReservation)r).setMustBeUsed(reservation.isMustBeUsed());
				} else if (r instanceof CurriculumOverrideReservation) {
					((CurriculumOverrideReservation)r).setAllowOverlap(reservation.isAllowOverlaps());
					((CurriculumOverrideReservation)r).setAlwaysExpired(reservation.isAlwaysExpired());
					((CurriculumOverrideReservation)r).setCanAssignOverLimit(reservation.isOverLimit());
					((CurriculumOverrideReservation)r).setMustBeUsed(reservation.isMustBeUsed());
				}
				offering.getReservations().add(r);
				if (r.getClasses() == null)
					r.setClasses(new HashSet<Class_>());
				else
					r.getClasses().clear();
				for (ReservationInterface.Clazz clazz: reservation.getClasses())
					r.getClasses().add(Class_DAO.getInstance().get(clazz.getId(), hibSession));
				if (r.getConfigurations() == null)
					r.setConfigurations(new HashSet<InstrOfferingConfig>());
				else
					r.getConfigurations().clear();
				for (ReservationInterface.Config config: reservation.getConfigs())
					r.getConfigurations().add(InstrOfferingConfigDAO.getInstance().get(config.getId(), hibSession));
				if (r instanceof IndividualReservation) {
					IndividualReservation ir = (IndividualReservation)r;
					if (ir.getStudents() == null)
						ir.setStudents(new HashSet<Student>());
					else
						ir.getStudents().clear();
					for (ReservationInterface.IdName student: ((ReservationInterface.IndividualReservation) reservation).getStudents()) {
						Student s = Student.findByExternalId(offering.getSessionId(), student.getAbbv());
						if (s != null)
							ir.getStudents().add(s);
					}
				} else if (r instanceof CourseReservation) {
					((CourseReservation)r).setCourse(CourseOfferingDAO.getInstance().get(((ReservationInterface.CourseReservation) reservation).getCourse().getId(), hibSession));
				} else if (r instanceof LearningCommunityReservation) {
					LearningCommunityReservation lcr = (LearningCommunityReservation)r;
					lcr.setGroup(StudentGroupDAO.getInstance().get(((ReservationInterface.LCReservation) reservation).getGroup().getId(), hibSession));
					lcr.setCourse(CourseOfferingDAO.getInstance().get(((ReservationInterface.LCReservation) reservation).getCourse().getId(), hibSession));
				} else if (r instanceof StudentGroupReservation) {
					((StudentGroupReservation)r).setGroup(StudentGroupDAO.getInstance().get(((ReservationInterface.GroupReservation) reservation).getGroup().getId(), hibSession));
				} else if (r instanceof CurriculumReservation) {
					ReservationInterface.Areas curriculum = ((ReservationInterface.CurriculumReservation)reservation).getCurriculum();
					CurriculumReservation cr = (CurriculumReservation)r;
					if (cr.getAreas() == null)
						cr.setAreas(new HashSet<AcademicArea>());
					else
						cr.getAreas().clear();
					for (ReservationInterface.IdName aa: curriculum.getAreas()) {
						cr.getAreas().add(AcademicAreaDAO.getInstance().get(aa.getId(), hibSession));
					}
					if (cr.getMajors() == null)
						cr.setMajors(new HashSet<PosMajor>());
					else
						cr.getMajors().clear();
					for (ReservationInterface.IdName mj: curriculum.getMajors()) {
						cr.getMajors().add(PosMajorDAO.getInstance().get(mj.getId(), hibSession));
					}
					if (cr.getClassifications() == null)
						cr.setClassifications(new HashSet<AcademicClassification>());
					else
						cr.getClassifications().clear();
					for (ReservationInterface.IdName clasf: curriculum.getClassifications()) {
						cr.getClassifications().add(AcademicClassificationDAO.getInstance().get(clasf.getId(), hibSession));
					}
					if (cr.getMinors() == null)
						cr.setMinors(new HashSet<PosMinor>());
					else
						cr.getMinors().clear();
					for (ReservationInterface.IdName mn: curriculum.getMinors()) {
						cr.getMinors().add(PosMinorDAO.getInstance().get(mn.getId(), hibSession));
					}
					if (cr.getConcentrations() == null)
						cr.setConcentrations(new HashSet<PosMajorConcentration>());
					else
						cr.getConcentrations().clear();
					for (ReservationInterface.IdName cc: curriculum.getConcentrations()) {
						cr.getConcentrations().add(PosMajorConcentrationDAO.getInstance().get(cc.getId(), hibSession));
					}
				}
				if (r.getUniqueId() == null)
					hibSession.persist(r);
				else
					hibSession.merge(r);
				hibSession.merge(r.getInstructionalOffering());
				if (permissionOfferingLockNeeded.check(user, offering))
					StudentSectioningQueue.offeringChanged(hibSession, user, offering.getSession().getUniqueId(), offering.getUniqueId());
				hibSession.flush();
				
				String className = ApplicationProperty.ExternalActionCourseOfferingReservationEdit.value();
		    	if (className != null && !className.trim().isEmpty()){
		    		ExternalCourseOfferingReservationEditAction editAction = (ExternalCourseOfferingReservationEditAction) Class.forName(className).getDeclaredConstructor().newInstance();
		    		editAction.performExternalCourseOfferingReservationEditAction(r.getInstructionalOffering(), hibSession);
		    	}
		    	
		        ChangeLog.addChange(
		        		hibSession,
		                sessionContext,
		                r.getInstructionalOffering(),
		                ChangeLog.Source.RESERVATION,
		                reservation.getId() == null ? ChangeLog.Operation.CREATE : ChangeLog.Operation.UPDATE,
		                r.getInstructionalOffering().getControllingCourseOffering().getSubjectArea(),
		                r.getInstructionalOffering().getDepartment());
		        hibSession.flush();
		        
				return r.getUniqueId();
			} finally {
				hibSession.close();
			}
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	@Override
	@PreAuthorize("checkPermission(#reservationId, 'Reservation', 'ReservationDelete')")
	public Boolean delete(Long reservationId) throws ReservationException, PageAccessException {
		try {
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			UserContext user = getSessionContext().getUser();
			try {
				Reservation reservation = ReservationDAO.getInstance().get(reservationId, hibSession);
				if (reservation == null)
					return false;
				InstructionalOffering offering = reservation.getInstructionalOffering();
				offering.getReservations().remove(reservation);
				hibSession.remove(reservation);
				hibSession.merge(offering);
				if (permissionOfferingLockNeeded.check(user, offering))
					StudentSectioningQueue.offeringChanged(hibSession, user, offering.getSession().getUniqueId(), offering.getUniqueId());
				hibSession.flush();
				
				String className = ApplicationProperty.ExternalActionCourseOfferingReservationEdit.value();
		    	if (className != null && !className.trim().isEmpty()){
		    		ExternalCourseOfferingReservationEditAction editAction = (ExternalCourseOfferingReservationEditAction) Class.forName(className).getDeclaredConstructor().newInstance();
		    		editAction.performExternalCourseOfferingReservationEditAction(offering, hibSession);
		    	}
		    	
		    	ChangeLog.addChange(
		        		hibSession,
		                sessionContext,
		                offering,
		                ChangeLog.Source.RESERVATION,
		                ChangeLog.Operation.DELETE,
		                offering.getControllingCourseOffering().getSubjectArea(),
		                offering.getDepartment());
		    	hibSession.flush();
			} finally {
				hibSession.close();
			}
			return true;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	private Long getAcademicSessionId() throws PageAccessException {
		UserContext user = getSessionContext().getUser();
		if (user == null) throw new PageAccessException(
				getSessionContext().isHttpSessionNew() ? MESSAGES.authenticationExpired() : MESSAGES.authenticationRequired());
		if (user.getCurrentAuthority() == null)
			throw new PageAccessException(MESSAGES.authenticationInsufficient());
		Long sessionId = user.getCurrentAcademicSessionId();
		if (sessionId == null) throw new PageAccessException(MESSAGES.authenticationNoSession());
		return sessionId;
	}

	@Override
	@PreAuthorize("checkPermission('ReservationAdd')")
	public Boolean canAddReservation() throws ReservationException, PageAccessException {
		return true;
	}

	@Override
	@PreAuthorize("checkPermission('Reservations')")
	public List<ReservationInterface> findReservations(ReservationFilterRpcRequest filter) throws ReservationException, PageAccessException {
		try {
			List<ReservationInterface> results = new ArrayList<ReservationInterface>();
			getSessionContext().setAttribute("Reservations.LastFilter", filter.toQueryString());
			org.hibernate.Session hibSession = CurriculumDAO.getInstance().getSession();
			String nameFormat = UserProperty.NameFormat.get(getSessionContext().getUser());
			try {
				for (Reservation reservation: ReservationFilterBackend.reservations(filter, getSessionContext())) {
					ReservationInterface r = convert(reservation, nameFormat, hibSession);
					r.setEditable(getSessionContext().hasPermission(reservation, Right.ReservationEdit));
					if (r instanceof ReservationInterface.OverrideReservation) {
						ReservationInterface.OverrideReservation o = (ReservationInterface.OverrideReservation)r;
						if (o.getType() != null && !o.getType().isEditable())
							r.setEditable(false);
					}
					results.add(r);
				}
			} finally {
				hibSession.close();
			}
			Collections.sort(results);
			return results;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}

	@Override
	@PreAuthorize("checkPermission('Reservations')")
	public String lastReservationFilter() throws ReservationException, PageAccessException {
		String filter = (String)getSessionContext().getAttribute("Reservations.LastFilter");
		return (filter == null ? "mode:\"Not Expired\"" : filter);
	}

	@Override
	public List<ReservationInterface.Curriculum> getCurricula(Long offeringId) throws ReservationException, PageAccessException {
		try {
			List<ReservationInterface.Curriculum> results = new ArrayList<ReservationInterface.Curriculum>();
			org.hibernate.Session hibSession = ReservationDAO.getInstance().getSession();
			try {
				for (Curriculum c : hibSession.createQuery(
						"select distinct c.classification.curriculum from CurriculumCourse c where c.course.instructionalOffering.uniqueId = :offeringId ", Curriculum.class)
						.setParameter("offeringId", offeringId).setCacheable(true).list()) {

					ReservationInterface.Curriculum curriculum = new ReservationInterface.Curriculum();
					curriculum.setAbbv(c.getAbbv());
					curriculum.setId(c.getUniqueId());
					curriculum.setName(c.getName());
					
					ReservationInterface.IdName area = new ReservationInterface.IdName();
					area.setAbbv(c.getAcademicArea().getAcademicAreaAbbreviation());
					area.setId(c.getAcademicArea().getUniqueId());
					area.setName(Constants.curriculaToInitialCase(c.getAcademicArea().getTitle()));
					curriculum.setArea(area);
					
					int limit = 0;
					for (CurriculumClassification cc: c.getClassifications()) {
						AcademicClassification classification = cc.getAcademicClassification();
						ReservationInterface.IdName clasf = new ReservationInterface.IdName();
						clasf.setId(classification.getUniqueId());
						clasf.setName(Constants.curriculaToInitialCase(classification.getName()));
						clasf.setAbbv(classification.getCode());
						clasf.setLimit(0);
						curriculum.getClassifications().add(clasf);
						for (CurriculumCourse cr: cc.getCourses())
							if (cr.getCourse().getInstructionalOffering().getUniqueId().equals(offeringId)) {
								limit += Math.round(cr.getPercShare() * cc.getNrStudents());
								clasf.setLimit(clasf.getLimit() + Math.round(cr.getPercShare() * cc.getNrStudents()));
							}
					}
					curriculum.setLimit(limit);
					Collections.sort(curriculum.getMajors());					
					
					for (PosMajor major: c.getMajors()) {
						ReservationInterface.IdName mj = new ReservationInterface.IdName();
						mj.setId(major.getUniqueId());
						mj.setAbbv(major.getCode());
						mj.setName(Constants.curriculaToInitialCase(major.getName()));
						curriculum.getMajors().add(mj);
					}
					Collections.sort(curriculum.getMajors());					
					
					results.add(curriculum);
				}
			} finally {
				hibSession.close();
			}
			Collections.sort(results);
			return results;
		} catch (PageAccessException e) {
			throw e;
		} catch (ReservationException e) {
			throw e;
		} catch (Exception e) {
			sLog.error(e.getMessage(), e);
			throw new ReservationException(e.getMessage());
		}
	}
	
	public ReservationServlet withSessionContext(SessionContext cx) {
		sessionContext = cx;
		return this;
	}
}
