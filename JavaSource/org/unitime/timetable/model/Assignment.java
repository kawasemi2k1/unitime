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
package org.unitime.timetable.model;


import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.Cache;
import org.hibernate.annotations.CacheConcurrencyStrategy;


import java.util.BitSet;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.Vector;

import org.cpsolver.coursett.model.Lecture;
import org.cpsolver.coursett.model.Placement;
import org.cpsolver.coursett.model.RoomLocation;
import org.cpsolver.coursett.model.TimeLocation;
import org.hibernate.Hibernate;
import org.hibernate.LazyInitializationException;
import org.hibernate.query.Query;
import org.unitime.timetable.defaults.ApplicationProperty;
import org.unitime.timetable.model.base.BaseAssignment;
import org.unitime.timetable.model.dao.AssignmentDAO;
import org.unitime.timetable.model.dao.AssignmentInfoDAO;
import org.unitime.timetable.model.dao.ConstraintInfoDAO;
import org.unitime.timetable.solver.ClassAssignmentProxy;
import org.unitime.timetable.solver.ui.TimetableInfo;
import org.unitime.timetable.util.Constants;
import org.unitime.timetable.util.duration.DurationModel;


/**
 * @author Tomas Muller, Stephanie Schluttenhofer
 */
@Entity
@Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
@Table(name = "assignment")
public class Assignment extends BaseAssignment implements ClassAssignmentProxy.AssignmentInfo {
	private static final long serialVersionUID = 1L;

/*[CONSTRUCTOR MARKER BEGIN]*/
	public Assignment () {
		super();
	}

	/**
	 * Constructor for primary key
	 */
	public Assignment (java.lang.Long uniqueId) {
		super(uniqueId);
	}

/*[CONSTRUCTOR MARKER END]*/
	
	@Transient
	public int[] getStartSlots() {
		int ret[] = new int[getTimePattern().getNrMeetings().intValue()];
		int idx=0;
		for (int i=0;i<Constants.DAY_CODES.length;i++)
			if ((getDays().intValue()&Constants.DAY_CODES[i])!=0)
				ret[idx++]=getStartSlot().intValue()+i*Constants.SLOTS_PER_DAY;
		return ret;
	}

	@Transient
	public Vector getStartSlotsVect() {
		Vector ret = new Vector();
		for (int i=0;i<Constants.DAY_CODES.length;i++)
			if ((getDays().intValue()&Constants.DAY_CODES[i])!=0)
				ret.addElement(Integer.valueOf(getStartSlot().intValue()+i*Constants.SLOTS_PER_DAY));
		return ret;
	}
	
	public transient Hashtable iAssignmentInfoCache = new Hashtable();

	public TimetableInfo getAssignmentInfo(String name) {
		TimetableInfo tInfo = (TimetableInfo)iAssignmentInfoCache.get(name);
		if (tInfo==null) {
			try {
				for (Iterator i=getAssignmentInfo().iterator();i.hasNext();) {
					AssignmentInfo info = (AssignmentInfo)i.next();
					if (info.getDefinition()!=null && info.getDefinition().getName().equals(name)) {
						tInfo = info.getInfo();
						break;
					}
				}
			} catch (LazyInitializationException e) {
				org.hibernate.Session session = (AssignmentInfoDAO.getInstance()).getSession();
				SolverInfoDef def = SolverInfoDef.findByName(session,name);
				if (def==null) return null;
				AssignmentInfo info = session.createQuery(
						"from AssignmentInfo where definition.uniqueId = :def and assignment.uniqueId = :assignment", AssignmentInfo.class)
						.setParameter("def", def.getUniqueId())
						.setParameter("assignment", getUniqueId())
						.uniqueResult();
				if (info==null) return null;
				tInfo = info.getInfo();
			}
			if (tInfo!=null) iAssignmentInfoCache.put(name, tInfo);
		}
		return tInfo;
	}
	
	public void cleastAssignmentInfoCache() { iAssignmentInfoCache.clear(); }
	
	public transient Hashtable iConstraintInfoCache = new Hashtable();

	public TimetableInfo getConstraintInfo(String name) {
		Vector infos = getConstraintInfos(name);
		if (infos==null || infos.isEmpty()) return null;
		return (TimetableInfo)infos.firstElement();
	}

	public Vector getConstraintInfos(String name) {
		Vector tInfos = (Vector)iConstraintInfoCache.get(name);
		if (tInfos==null) {
			try {
				tInfos = new Vector();
				for (Iterator i=getConstraintInfo().iterator();i.hasNext();) {
					ConstraintInfo info = (ConstraintInfo)i.next();
					if (info.getDefinition()!=null && info.getDefinition().getName().equals(name)) {
						TimetableInfo tInfo = info.getInfo();
						if (tInfo!=null)
							tInfos.add(tInfo);
					}
				}
			} catch (LazyInitializationException e) {
				org.hibernate.Session session = (ConstraintInfoDAO.getInstance()).getSession();
				Query<ConstraintInfo> q = session.createQuery("select distinct c from ConstraintInfo as c inner join c.assignments as a where " +
						"c.definition.name=:name and a.uniqueId=:assignmentId", ConstraintInfo.class);
				q.setParameter("assignmentId", getUniqueId());
				q.setParameter("name", name);
				tInfos = new Vector();
				for (ConstraintInfo info: q.list()) {
					TimetableInfo tInfo = info.getInfo();
					if (tInfo!=null)
						tInfos.add(tInfo);
				}
			}
			if (tInfos!=null) iConstraintInfoCache.put(name, tInfos);
		}
		return tInfos;
	}
	
	public Hashtable getConstraintInfoTable(String name) {
		Hashtable ret = new Hashtable();
		try {
			for (Iterator i=getConstraintInfo().iterator();i.hasNext();) {
				ConstraintInfo info = (ConstraintInfo)i.next();
				if (info.getDefinition()!=null && info.getDefinition().getName().equals(name)) {
					TimetableInfo tInfo = info.getInfo();
					if (tInfo!=null)
						ret.put(info, tInfo);
				}
			}
		} catch (LazyInitializationException e) {
			org.hibernate.Session session = (ConstraintInfoDAO.getInstance()).getSession();
			Query<ConstraintInfo> q = session.createQuery("select distinct c from ConstraintInfo as c inner join c.assignments as a where " +
					"c.definition.name=:name and a.uniqueId=:assignmentId", ConstraintInfo.class);
			q.setParameter("assignmentId", getUniqueId());
			q.setParameter("name", name);
			for (ConstraintInfo info: q.list()) {
				TimetableInfo tInfo = info.getInfo();
				if (tInfo!=null)
					ret.put(info, tInfo);
			}
		}
		return ret;
	}
	
	private int iSlotsPerMtg = -1;
	public void setSlotsPerMtg(int slotsPerMtg) {
		iSlotsPerMtg = slotsPerMtg;
	}
	@Transient
	public int getSlotPerMtg() {
		if (iSlotsPerMtg>=0) return iSlotsPerMtg;
		TimePattern pattern = getTimePattern();
		iSlotsPerMtg = pattern.getSlotsPerMtg().intValue();
		if (pattern.isExactTime()) {
			DurationModel dm = getClazz().getSchedulingSubpart().getInstrOfferingConfig().getDurationModel();
			int minsPerMeeting = dm.getExactTimeMinutesPerMeeting(getClazz().getSchedulingSubpart().getMinutesPerWk(), getDatePattern(), getDays());
			iSlotsPerMtg = ExactTimeMins.getNrSlotsPerMtg(minsPerMeeting);
		}
		return iSlotsPerMtg;
	}
	
	private int iBreakTime = -1;
	public void setBreakTime(int breakTime) {
		iBreakTime = breakTime;
	}
	@Transient
	public int getBreakTime() {
		if (iBreakTime>=0) return iBreakTime;
		TimePattern pattern = getTimePattern();
		iBreakTime = pattern.getBreakTime().intValue();
		if (pattern.isExactTime()) {
			DurationModel dm = getClazz().getSchedulingSubpart().getInstrOfferingConfig().getDurationModel();
			int minsPerMeeting = dm.getExactTimeMinutesPerMeeting(getClazz().getSchedulingSubpart().getMinutesPerWk(), getDatePattern(), getDays());
			iBreakTime = ExactTimeMins.getBreakTime(minsPerMeeting); 
		}
		return iBreakTime;
	}

	private transient TimeLocation iTimeLocation = null;
	@Transient
	public TimeLocation getTimeLocation() {
		if (iPlacement!=null) return iPlacement.getTimeLocation();
		if (iTimeLocation==null) {
			DatePattern datePattern = getDatePattern();
			iTimeLocation = new TimeLocation(
				getDays().intValue(),
				getStartSlot().intValue(),
				getSlotPerMtg(),
				0,0,
				(datePattern == null ? null : datePattern.getUniqueId()),
				(datePattern == null ? "?" : datePattern.getName()),
				(datePattern == null ? new BitSet() : datePattern.getPatternBitSet()),
				getBreakTime()
				);
			iTimeLocation.setTimePatternId(getTimePattern().getUniqueId());
		}
		return iTimeLocation;
	}
	@Transient
	public Vector getRoomLocations() {
		Vector ret = new Vector();
		for (Iterator i=getRooms().iterator();i.hasNext();) {
			Location room = (Location)i.next();
			RoomLocation roomLocation = new RoomLocation(
					room.getUniqueId(),
					room.getLabel(),
					(room instanceof Room? ((Room)room).getBuilding().getUniqueId() : null),
					0,
					room.getCapacity().intValue(),
					room.getCoordinateX(),
					room.getCoordinateY(),
					room.isIgnoreTooFar().booleanValue(),
					null);
			ret.addElement(roomLocation);
		}
		return ret;
	}
	
	private transient Placement iPlacement = null;
	@Transient
	public Placement getPlacement() {
		if (iPlacement!=null) return iPlacement;
		TimeLocation timeLocation = getTimeLocation();
		Vector timeLocations = new Vector(1); timeLocations.addElement(timeLocation);
		Vector roomLocations = getRoomLocations();
    	Lecture lecture = new Lecture(getClassId(), (getSolution()==null || getSolution().getOwner()==null?null:getSolution().getOwner().getUniqueId()), (getClazz()==null?null:getClazz().getSchedulingSubpart().getUniqueId()), getClassName(), timeLocations, roomLocations, roomLocations.size(), new Placement(null,timeLocation,roomLocations),
    			(getClazz() == null ? 0 : getClazz().getExpectedCapacity()), (getClazz() == null ? 0 : getClazz().getMaxExpectedCapacity()), (getClazz() == null ? 1.0f : getClazz().getRoomRatio()));
		if (getClazz()!=null)
			lecture.setNote(getClazz().getNotes());
		iPlacement = (Placement)lecture.getInitialAssignment();
		iPlacement.setVariable(lecture);
		iPlacement.setAssignmentId(getUniqueId());
		lecture.setBestAssignment(iPlacement, 0);
		if (getSolution()!=null && getSolution().isCommited()!=null)
			lecture.setCommitted(getSolution().isCommited().booleanValue());
        iPlacement.setAssignment(this);
		return iPlacement;
	}
	
	public String toString() {
		return getClassName()+" "+getPlacement().getName();
	}
	
	@Transient
	public DatePattern getDatePattern() {
		DatePattern dp = super.getDatePattern();
		if (dp != null && !Hibernate.isInitialized(dp.getSession()))
			return (DatePattern)AssignmentDAO.getInstance().getSession().merge(dp);
		if (dp == null && getClazz() != null)
			dp = getClazz().effectiveDatePattern();
		return dp;
	}
	
	@Transient
	public String getClassName() {
		if (super.getClassName()!=null) return super.getClassName();
		return getClazz().getClassLabel(ApplicationProperty.SolverShowClassSufix.isTrue(), ApplicationProperty.SolverShowConfiguratioName.isTrue());
	}
	
	public String getClassName(boolean showSuffix) {
		return getClazz().getClassLabel(showSuffix);
	}
	
	@Transient
	public Set<Location> getRooms() {
		try {
			return super.getRooms();
		} catch (LazyInitializationException e) {
			(AssignmentDAO.getInstance()).getSession().merge(this);
			return super.getRooms();
		}
	}
	
	public static double getDistance(Assignment a1, Assignment a2) {
		double dist = 0.0;
		for (Iterator i1=a1.getRooms().iterator();i1.hasNext();) {
			Location r1 = (Location)i1.next();
			for (Iterator i2=a2.getRooms().iterator();i2.hasNext();) {
				Location r2 = (Location)i2.next();
				dist = Math.max(dist,r1.getDistance(r2));
			}
		}
		return dist;
	}
	
	public boolean isInConflict(Assignment other) {
		return isInConflict(this, other, true);
	}
	
	public static boolean isInConflict(Assignment a1, Assignment a2, boolean useDistances) {
		if (a1==null || a2==null) return false;
	       TimeLocation t1=a1.getTimeLocation(), t2=a2.getTimeLocation();
	       if (!t1.shareDays(t2)) return false;
	       if (!t1.shareWeeks(t2)) return false;
	       if (t1.shareHours(t2)) return true;
	       if (!useDistances) return false;
	       int s1 = t1.getStartSlot(), s2 = t2.getStartSlot();
	       if (s1+t1.getNrSlotsPerMeeting()!=s2 &&
	           s2+t2.getNrSlotsPerMeeting()!=s1) return false;
	       double distance = getDistance(a1,a2);
	       if (distance <= a1.getSolution().getProperties().getPropertyDouble("Student.DistanceLimit",67.0)) return false;
	       if (distance <= a1.getSolution().getProperties().getPropertyDouble("Student.DistanceLimit75min",100.0) && (
	           (t1.getLength()==18 && s1+t1.getLength()==s2) ||
	           (t2.getLength()==18 && s2+t2.getLength()==s1)))
	           return false;
	       return true;
   }
	
	@Transient
	public int getMinutesPerMeeting() {
		TimePattern pattern = getTimePattern();
		if (pattern.isExactTime()) {
			DurationModel dm = getClazz().getSchedulingSubpart().getInstrOfferingConfig().getDurationModel();
			return dm.getExactTimeMinutesPerMeeting(getClazz().getSchedulingSubpart().getMinutesPerWk(), getDatePattern(), getDays());
		} else {
			return pattern.getMinPerMtg();
		}
	}
	
    public ClassEvent generateCommittedEvent(ClassEvent event, boolean createNoRoomMeetings) {
    	Class_ clazz = getClazz();
        if (event==null) {
            event = new ClassEvent();
            event.setClazz(clazz); clazz.setEvent(event);
            if (getClazz().getSession().getStatusType().isTestSession()) return null;
        }
        event.setEventName(getClassName());
        event.setMinCapacity(clazz.getClassLimit(this));
        event.setMaxCapacity(clazz.getClassLimit(this));
        
        boolean changePast = ApplicationProperty.ClassAssignmentChangePastMeetings.isTrue();
		Calendar cal = Calendar.getInstance(Locale.US);
		cal.set(Calendar.HOUR_OF_DAY, 0);
		cal.set(Calendar.MINUTE, 0);
		cal.set(Calendar.SECOND, 0);
		cal.set(Calendar.MILLISECOND, 0);
		Date today = cal.getTime();
        
		if (event.getMeetings() != null) {
			if (changePast) {
				event.getMeetings().clear();
			} else {
		    	for (Iterator<Meeting> i = event.getMeetings().iterator(); i.hasNext(); )
		    		if (!i.next().getMeetingDate().before(today)) i.remove();
			}
		} else {
			event.setMeetings(new HashSet());
        }
		
		DurationModel dm = getClazz().getSchedulingSubpart().getInstrOfferingConfig().getDurationModel();
		TimeLocation time = getTimeLocation();
		for (Date meetingDate: dm.getDates(clazz.getSchedulingSubpart().getMinutesPerWk(), getDatePattern(), time.getDayCode(), getMinutesPerMeeting())) {
            if (changePast || !meetingDate.before(today)) {
                boolean created = false;
                for (Iterator i=getRooms().iterator();i.hasNext();) {
                    Location location = (Location)i.next();
                    if (location.getPermanentId()!=null) {
                        Meeting m = new Meeting();
                        m.setMeetingDate(meetingDate);
                        m.setStartPeriod(time.getStartSlot());
                        m.setStartOffset(0);
                        m.setStopPeriod(time.getStartSlot()+time.getLength());
                        m.setStopOffset(-time.getBreakTime());
                        m.setClassCanOverride(false);
                        m.setLocationPermanentId(location.getPermanentId());
                        m.setStatus(Meeting.Status.APPROVED);
                        m.setApprovalDate(getSolution().getCommitDate());
                        m.setEvent(event);
                        event.getMeetings().add(m);
                        created = true;
                    }
                }
                if (!created && createNoRoomMeetings) {
                    Meeting m = new Meeting();
                    m.setMeetingDate(meetingDate);
                    m.setStartPeriod(time.getStartSlot());
                    m.setStartOffset(0);
                    m.setStopPeriod(time.getStartSlot()+time.getLength());
                    m.setStopOffset(-time.getBreakTime());
                    m.setClassCanOverride(false);
                    m.setLocationPermanentId(null);
                    m.setStatus(Meeting.Status.APPROVED);
                    m.setApprovalDate(getSolution().getCommitDate());
                    m.setEvent(event);
                    event.getMeetings().add(m);
                }
            }
		}
		
		/*
        DatePattern dp = getDatePattern();
        cal.setTime(dp.getStartDate()); cal.setLenient(true);
        TimeLocation time = getTimeLocation(); 
        EventDateMapping.Class2EventDateMap class2eventDates = EventDateMapping.getMapping(clazz.getSessionId());
        for (int idx=0;idx<dp.getPattern().length();idx++) {
            if (dp.getPattern().charAt(idx)=='1') {
                boolean offered = false;
                switch (cal.get(Calendar.DAY_OF_WEEK)) {
                    case Calendar.MONDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_MON]) != 0); break;
                    case Calendar.TUESDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_TUE]) != 0); break;
                    case Calendar.WEDNESDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_WED]) != 0); break;
                    case Calendar.THURSDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_THU]) != 0); break;
                    case Calendar.FRIDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_FRI]) != 0); break;
                    case Calendar.SATURDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_SAT]) != 0); break;
                    case Calendar.SUNDAY : offered = ((time.getDayCode() & Constants.DAY_CODES[Constants.DAY_SUN]) != 0); break;
                }
                Date meetingDate = class2eventDates.getEventDate(cal.getTime());
                if (offered && (changePast || !meetingDate.before(today))) {
                    boolean created = false;
                    for (Iterator i=getRooms().iterator();i.hasNext();) {
                        Location location = (Location)i.next();
                        if (location.getPermanentId()!=null) {
                            Meeting m = new Meeting();
                            m.setMeetingDate(meetingDate);
                            m.setStartPeriod(time.getStartSlot());
                            m.setStartOffset(0);
                            m.setStopPeriod(time.getStartSlot()+time.getLength());
                            m.setStopOffset(-time.getBreakTime());
                            m.setClassCanOverride(false);
                            m.setLocationPermanentId(location.getPermanentId());
                            m.setStatus(Meeting.Status.APPROVED);
                            m.setApprovalDate(getSolution().getCommitDate());
                            m.setEvent(event);
                            event.getMeetings().add(m);
                            created = true;
                        }
                    }
                    if (!created && createNoRoomMeetings) {
                        Meeting m = new Meeting();
                        m.setMeetingDate(meetingDate);
                        m.setStartPeriod(time.getStartSlot());
                        m.setStartOffset(0);
                        m.setStopPeriod(time.getStartSlot()+time.getLength());
                        m.setStopOffset(-time.getBreakTime());
                        m.setClassCanOverride(false);
                        m.setLocationPermanentId(null);
                        m.setStatus(Meeting.Status.APPROVED);
                        m.setApprovalDate(getSolution().getCommitDate());
                        m.setEvent(event);
                        event.getMeetings().add(m);
                    }
                }
            }
            cal.add(Calendar.DAY_OF_YEAR, 1);
        }*/
        return event;
    }
    
    public boolean shareDays(ClassAssignmentProxy.AssignmentInfo another) {
        return ((getDays() & another.getDays()) !=0 );
    }

    public boolean shareHours(ClassAssignmentProxy.AssignmentInfo another) {
    	return (getStartSlot() + getSlotPerMtg() > another.getStartSlot()) && (another.getStartSlot() + another.getSlotPerMtg() > getStartSlot());
    }

    public boolean shareWeeks(ClassAssignmentProxy.AssignmentInfo another) {
    	return getDatePattern() == null || another.getDatePattern() == null || getDatePattern().getPatternBitSet().intersects(another.getDatePattern().getPatternBitSet());
    }

    public boolean overlaps(ClassAssignmentProxy.AssignmentInfo another) {
        return shareDays(another) && shareHours(another) && shareWeeks(another);
    }    
    
	@Transient
    public boolean isCommitted() {
    	if (getSolution() == null) return false;
    	return getSolution().getCommitDate() != null;
    }
	
	@Transient
	public Long getClassId() {
		return getClazz() == null ? null : getClazz().getUniqueId();
	}
}
