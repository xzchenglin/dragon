package dragon.service;

import dragon.comm.Pair;
import dragon.comm.Utils;
import dragon.comm.crypto.CryptoALG;
import dragon.comm.crypto.CryptoUtils;
import dragon.model.food.Record;
import dragon.model.food.Restaurant;
import dragon.model.food.Stat;
import dragon.model.food.Vote;
import dragon.utils.ConfigHelper;
import dragon.utils.DbHelper;
import dragon.utils.QueueHelper;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import javax.crypto.SecretKey;
import javax.ejb.Stateless;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.*;

/**
 * Created by lin.cheng on 6/1/15.
 */
@Stateless
public class BizBean implements BizIntf {

    static Log logger = LogFactory.getLog(BizBean.class);
    static final String KEY = "KEY";
    static final Double GRAVITY = 0.7;
    static final Integer BASE = 20;

    public int importRestaurants(String csv) {
        List<String[]> data = new ArrayList<String[]>();
        Utils.parseHeaderAndData(csv, data, true);

        Connection conn = null;

        try {
            conn = DbHelper.getConn();
            for (String[] item : data) {
                Restaurant r = new Restaurant(item[0], item[1], Long.parseLong(item[2]), item[4], item[5]);
                saveRestaurant(r, conn);
            }
        } catch (Exception e) {
            logger.error("", e);
        } finally {
            DbHelper.closeConn(conn);
        }

        return data.size();
    }

    //Thread safe
    public Long saveRestaurant(Restaurant r, Connection conn) {

        String key = r.getName();
        boolean reuse = conn != null;//Remember to close connection outside
        Long id = 0L;

        logger.info("Saving biz:" + r.getName());

        try {
            if (!reuse) {
                conn = DbHelper.getConn();
            }
            conn.setAutoCommit(false);
            Statement st1 = conn.createStatement();

            st1.execute("LOCK TABLE dragon_restaurant IN ACCESS EXCLUSIVE MODE");

            logger.debug("Locked: " + Thread.currentThread().getId());

            id = DbHelper.runWithSingleResult2(conn, "select id from dragon_restaurant where name =?", key);

            if (id != null) {
                Long exfactor = DbHelper.runWithSingleResult2(conn, "select factor from dragon_restaurant where name =?", key);
                if(Long.compare(r.getFactor(), exfactor) != 0) {
                    logger.info("Factor changed:" + r.getName());
                    DbHelper.runUpdate2(conn, "update dragon_restaurant set factor=? where name=?", r.getFactor(), key);
                }
                logger.debug("No qualified changes, skip:" + r.getName());
                //...need to add more if want to be able to update more columns
            } else {
                logger.info("Save new:" + r.getName());

                id = DbHelper.getNextId(conn);

                logger.debug("Add: " + Thread.currentThread().getId());

                DbHelper.runUpdate2(conn, "insert into dragon_restaurant (name,link,factor,id,category,alias) VALUES(?,?,?,?,?,?)",
                        key, r.getLink(), r.getFactor(), id, r.getCategory(), StringUtils.isNotBlank(r.getAlias()) ? r.getAlias() : key);
            }

            conn.commit();

            logger.debug("Commit and unlock: " + Thread.currentThread().getId());

        } catch (Exception e) {
            logger.error("", e);
        } finally {
            if (!reuse) {
                DbHelper.closeConn(conn);
            }
        }

        return id;
    }

    //Not thread safe
    public Boolean vote(Vote v, Boolean resend) {

        Long t1 = System.currentTimeMillis();

        logger.info(v.toString());

        Long recId = v.getRecId();
        Record rec = getRecord(recId);
        if (rec == null) {
            logger.error("Record not found: " + recId);
            return false;
        }

        Long resId = rec.getResid();
        Restaurant res = getRestaurant(new Pair<String, Object>("id", resId));
        if (res == null) {
            logger.error("Restaurant not found: " + resId);
            return false;
        }

        Long t2 = System.currentTimeMillis();
        logger.debug("getRec takes: " + (t2 - t1));

        saveVote(v, null);

        Long t3 = System.currentTimeMillis();
        logger.debug("saveVote takes: " + (t3 - t2));

        if (v.getResult() == Vote.Result.killme && resend) {

            if (!rec.getVeto() && System.currentTimeMillis() - rec.getGoTime() < 1000 * 60 * 60) {//within 1 hour
                rec.setVeto(true);
                try {
                    saveRecord(rec);
                } catch (Exception e) {
                    logger.error(e.getMessage());
                    return false;
                }
                Long t4 = System.currentTimeMillis();
                logger.debug("saveRecord takes: " + (t4 - t3));

                //re-pickup
                sendLunchEmail("重新选一家，因为" + v.getEmail().split("@")[0] + "表示打死都不去。", rec.getgId());

                Long t5 = System.currentTimeMillis();
                logger.debug("Email takes: " + (t5 - t4));
            } else {
                logger.info("Already vetoed or time passed.");
                return false;
            }
        }

        return true;
    }

    public List<Restaurant> getRestaurants(String condition) {
        Connection conn = null;
        List<Restaurant> list = new ArrayList<Restaurant>();
        try {
            conn = DbHelper.getConn();
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("select * from dragon_restaurant" + (StringUtils.isBlank(condition) ? "" : " where " + condition));

            while (rs.next()) {
                list.add(new Restaurant(rs.getString("name"), rs.getString("link"), rs.getLong("factor"), rs.getLong("id")
                ,rs.getString("alias"), rs.getString("category")));
            }
        } catch (Exception e) {
            logger.error("");
        } finally {
            DbHelper.closeConn(conn);
        }
        return list;
    }

    public List<Restaurant> getRestaurants(Long gid) {
        Connection conn = null;
        List<Restaurant> list = new ArrayList<Restaurant>();
        try {
            conn = DbHelper.getConn();
            PreparedStatement st = conn.prepareStatement("select r.name,r.link,gr.factor,r.id,r.alias,r.category from dragon_restaurant r,dragon_group g,dragon_group_rest gr " +
                    "where gr.res_id=r.id and gr.g_id=g.id and g.id=?");
            DbHelper.setParameters(st, gid);
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                list.add(new Restaurant(rs.getString("name"), rs.getString("link"), rs.getLong("factor"), rs.getLong("id")
                        ,rs.getString("alias"), rs.getString("category")));
            }
        } catch (Exception e) {
            logger.error("");
        } finally {
            DbHelper.closeConn(conn);
        }
        return list;
    }

    public Restaurant pickRestaurant(Long gid) {

        Long t1 = System.currentTimeMillis();

        List<Restaurant> list = getRestaurants(gid);
        if (list == null || list.size() == 0) {
            logger.info("Restaurant not found.");
            return null;
        }

        Long t2 = System.currentTimeMillis();
        logger.debug("getRestaurants takes: " + (t2 - t1));

        Map<String, Stat> ss = stat(gid, 0, false);
        long totalWeight = 0;
        List<Long> preIds = DbHelper.getFirstColumnList(null, "select distinct res_id,id from dragon_record where g_id=" + gid + " order by id desc limit ?", 3);

        Long t3 = System.currentTimeMillis();
        logger.debug("stat takes: " + (t3 - t2));

        for (Restaurant r : list) {
            if (preIds.contains(r.getId()) && list.size() > preIds.size()) {
                continue;
            }
            totalWeight += getWeight(ss, r);
        }

        long rdm = Math.abs(new Random().nextLong());
        long selected = rdm % totalWeight;
        long pos = 0;
        for (Restaurant r : list) {

            if (preIds.contains(r.getId()) && list.size() > preIds.size()) {
                logger.info(r.getName() + " skipped.");
                continue;
            }

            if (pos <= selected && selected < pos + getWeight(ss, r)) {
                Long t4 = System.currentTimeMillis();
                logger.debug("pick up takes: " + (t4 - t3));
                logger.info("Picked up: " + r.getName());
                return r;
            }
            pos += getWeight(ss, r);
        }

        logger.info("Not able to find a restaurant.");
        return null;
    }

    public void sendLunchEmail(String reason){
        List<Long> gids = DbHelper.getFirstColumnList(null, "select id from dragon_group where active=true");
        for(Long gid:gids){
            sendLunchEmail(reason, gid);
        }
    }

    public void sendLunchEmail(String reason, Long gid) {

        logger.info("Sending lunch mail for: " + gid);
        List<String> mails = getMails(gid);

        if (mails != null && mails.size() > 0) {

            logger.info("Valid mails found.");

            Restaurant r = pickRestaurant(gid);
            if (r == null) {
                logger.info("Biz not found, no email sent.");
                return;
            }

            Record rec = new Record();
            rec.setResid(r.getId());
            rec.setgId(gid);
            Long id = saveRecord(rec).getId();

            String gname = DbHelper.runWithSingleResult2(null, "select alias from dragon_group where id=?", gid);

            QueueHelper qh = new QueueHelper();

            try {
                qh.createDeliveryConnection(100);
                qh.initializeMessage();
                qh.initializeQueue("jms/EmailQueue");
                qh.addParameter("title", "[" + gname + "] " + r.getAlias());

                for (String mail : mails) {
                    qh.addParameter("to", mail);
                    qh.addParameter("body", buildBody(mail, r, reason, id, gid));
                    qh.sendMsg();
                    logger.info("Msg sent to queue:" + gname + " -> " + mail);
                }
            } catch (Exception e) {
                logger.error("", e);
            } finally {
                if (qh != null) {
                    try {
                        qh.close();
                    } catch (Exception ex) {
                        logger.error("", ex);
                    }
                }
            }
        }
    }

    public Map<String, Stat> stat(long gid, long exId, Boolean sort) {
        Connection conn = null;
        Map<String, Stat> ret = new HashMap<String, Stat>();

        try {
            conn = DbHelper.getConn();
            PreparedStatement st = conn.prepareStatement("select res.name,res.factor,v.vote,count(*) from dragon_restaurant res " +
                    "inner join dragon_record r on r.res_id=res.id left join dragon_vote v on v.rec_id=r.id " +
                    "where r.g_id =? group by res.name,res.factor,v.vote order by count(*)");
            DbHelper.setParameters(st, gid);
            ResultSet rs = st.executeQuery();

            while (rs.next()) {
                String name = rs.getString("name");
                int factor = rs.getInt("factor");
                int score = BASE;
                Object vote = rs.getObject("vote");
                int cnt = rs.getInt("count");

                Vote.Result vr = vote == null ? null : Vote.Result.values()[(Integer) vote];
                Stat s = null;
                if (!ret.containsKey(name)) {
                    s = new Stat(name, factor, score);
                    ret.put(name, s);
                } else {
                    s = ret.get(name);
                }

                if (vr == Vote.Result.dislike) {
                    s.setDisliked(s.getDisliked() + cnt);
                }
                if (vr == Vote.Result.like) {
                    s.setLiked(s.getLiked() + cnt);
                }
                if (vr == Vote.Result.killme) {
                    s.setVetoed(s.getVetoed() + cnt);
                }
                if(vr != null) {
                    s.setScore(s.getScore() + vr.getScore() * cnt);
                }
            }

            PreparedStatement st2 = conn.prepareStatement("select res.name,count(*) from dragon_restaurant res inner join dragon_record r on r.res_id=res.id " +
                            "where r.veto = false and r.g_id = ? and r.id <> ? group by res.name");
            DbHelper.setParameters(st2, gid, exId);
            rs = st2.executeQuery();

            while (rs.next()){
                String name = rs.getString("name");
                int cnt = rs.getInt("count");

                if(ret.containsKey(name)){
                    Stat s = ret.get(name);
                    s.setVisited(cnt);
                    s.setScore(s.getScore() + (int) Math.round(cnt / Math.pow(s.getFactor(), GRAVITY)));
                }
            }

            setGroupFactor(conn, gid, ret);

            if(sort) {
                ValueComparator bvc = new ValueComparator(ret);
                Map<String, Stat> sorted = new TreeMap<String, Stat>(bvc);
                sorted.putAll(ret);

                return sorted;
            } else {
                return ret;
            }
        } catch (Exception e) {
            logger.error(e.getMessage());
        } finally {
            DbHelper.closeConn(conn);
        }

        return ret;
    }

    public Map<String, Stat> stat2(long gid, int days) {
        Connection conn = null;
        Map<String, Stat> ret = new HashMap<String, Stat>();
        long gotime = System.currentTimeMillis() - 1000*3600*24L * days;

        try {
            conn = DbHelper.getConn();
            PreparedStatement st = conn.prepareStatement("select res.name,res.factor,v.vote,count(*) from dragon_restaurant res " +
                    "inner join dragon_record r on r.res_id=res.id left join dragon_vote v on v.rec_id=r.id " +
                    "where r.g_id=? and r.go_time>? group by res.name,res.factor,v.vote order by count(*)");
            DbHelper.setParameters(st, gid, gotime);
            ResultSet rs = st.executeQuery();
            while (rs.next()) {
                String name = rs.getString("name");
                int factor = rs.getInt("factor");
                Object vote = rs.getObject("vote");
                int cnt = rs.getInt("count");

                Vote.Result vr = vote == null ? null : Vote.Result.values()[(Integer) vote];
                Stat s = null;
                if (!ret.containsKey(name)) {
                    s = new Stat(name, factor, 0);
                    ret.put(name, s);
                } else {
                    s = ret.get(name);
                }

                if (vr == Vote.Result.dislike) {
                    s.setDisliked(s.getDisliked() + cnt);
                }
                if (vr == Vote.Result.like) {
                    s.setLiked(s.getLiked() + cnt);
                }
                if (vr == Vote.Result.killme) {
                    s.setVetoed(s.getVetoed() + cnt);
                }
                if(vr != null) {
                    s.setScore(s.getScore() + vr.getScore() * cnt);
                }
            }

            PreparedStatement st2 = conn.prepareStatement("select res.name,count(*) from dragon_restaurant res inner join dragon_record r on r.res_id=res.id " +
                    "where r.veto = false and r.g_id =? and r.go_time > ? group by res.name");
            DbHelper.setParameters(st2, gid, gotime);
            rs = st2.executeQuery();

            while (rs.next()){
                String name = rs.getString("name");
                int cnt = rs.getInt("count");

                if(ret.containsKey(name)){
                    Stat s = ret.get(name);
                    s.setVisited(cnt);
                    s.setScore(s.getScore() + (int) Math.round(cnt / Math.pow(s.getFactor(), GRAVITY)));
                }
            }

            setGroupFactor(conn, gid, ret);

            ValueComparator bvc =  new ValueComparator(ret);
            Map<String, Stat> sorted = new TreeMap<String, Stat>(bvc);
            sorted.putAll(ret);

            return sorted;
        } catch (Exception e) {
            logger.error(e.getMessage());
        } finally {
            DbHelper.closeConn(conn);
        }

        return ret;
    }

    private void setGroupFactor(Connection conn, long gid, final Map<String, Stat> ss) throws SQLException {

        if(conn == null || ss == null || ss.size() == 0){
            return;
        }

        PreparedStatement st = conn.prepareStatement("select r.name,gr.factor from dragon_restaurant r inner join dragon_group_rest gr on r.id=gr.res_id where gr.g_id=?");
        DbHelper.setParameters(st, gid);
        ResultSet rs = st.executeQuery();

        while (rs.next()){
            String name = rs.getString("name");
            Integer factor = rs.getInt("factor");

            if(ss.containsKey(name)){
                Stat s = ss.get(name);
                if(factor != null && factor > 0){
                    s.setFactor(factor);
                }
            }
        }

    }

    private String buildBody(String mail, Restaurant r, String reason, Long id, Long gid) {
        String server = ConfigHelper.instance().getConfig("server");
        String port = ConfigHelper.instance().getConfig("port");

        StringBuilder sb = new StringBuilder();

        if (StringUtils.isNotBlank(reason)) {
            sb.append(reason).append("<br><br>");
        }

        sb.append(r.getLink()).append(" <br><br>");

        Map<String, Stat> ss = stat(gid, id, false);
        Stat s = ss.get(r.getName());
        if (s != null) {
            s.setScore(s.getScore());
            sb.append(s.toString()).append("<br><br>");
        }

        String url = "http://" + server + ":" + port + "/dragon/rest/biz/";

        sb.append("<a href=\'").append(url).append("vote?mail=").append(mail).append("&id=").append(id).append("&vote=2").append("\'/>").append("靠谱 ☺</a>").append("<br>");
        sb.append("<a href=\'").append(url + "vote?mail=" + mail + "&id=" + id + "&vote=1").append("\'/>").append("一般 ☹</a>").append("<br>");
        sb.append("<a href=\'").append(url).append("vote?mail=").append(mail).append("&id=").append(id).append("&vote=0").append("\'/>").append("打死都不去").append("</a>")
                .append(" (不搭伙的求别闹:( )").append("<br><br>");
        sb.append("<a href=\'").append(url + "group/unsub?mail=" + mail).append("&gid=").append(gid).append("\'/>").append("取关!</a>").append("<br>");

        return sb.toString();
    }

    public Record saveRecord(Record r) {

        Long id = r.getId();
        if (id == null || id <= 0) {
            id = DbHelper.getNextId(null);
            DbHelper.runUpdate2(null, "insert into dragon_record (id,res_id,go_time,g_id) VALUES(?,?,?,?)",
                    id, r.getResid(), System.currentTimeMillis(), r.getgId());
        } else {
            DbHelper.runUpdate2(null, "update dragon_record set veto=? where id=?", r.getVeto(), r.getId());
        }
        return getRecord(id);
    }

    @Override
    public String saveSecret(String key, String value) {

        logger.info("Saving secret:" + key);

        String name = DbHelper.runWithSingleResult2(null, "select name from dragon_secret where name =?", key);
        String custKey = getOrCreateKey();
        String enValue = null;
        try {
            enValue = CryptoUtils.encryptPwd(value, custKey);
        } catch (Exception e) {
            logger.error("", e);
            enValue = value;
        }

        if(name == null) {
            DbHelper.runUpdate2(null, "insert into dragon_secret (name,value) VALUES(?,?)", key, enValue);
        } else {
            DbHelper.runUpdate2(null, "update dragon_secret set value=? where name=?", enValue, key);
        }

        return enValue;
    }

    @Override
    public String getSecret(String key) {
        String enValue = DbHelper.runWithSingleResult2(null, "select value from dragon_secret where name =?", key);

        if(enValue == null){
            return null;
        }

        String custKey = getOrCreateKey();
        String value = null;
        try {
            value = CryptoUtils.decryptPwd(enValue,custKey);
        } catch (Exception e) {
            logger.error("", e);
            value = enValue;
        }

        return value;
    }

    private String getOrCreateKey(){

        String key = DbHelper.runWithSingleResult("select value from dragon_secret where name ='" + KEY + "'", null);

        if(key != null){
            return key;
        }

        SecretKey secretKey = null;
        try {
            secretKey = CryptoUtils.generateKey(CryptoALG.AES.getSpecName());
        } catch (NoSuchAlgorithmException e) {
            logger.error("", e);
        }
        String strKey = CryptoUtils.secretKeyToString(secretKey);
        DbHelper.runUpdate2(null, "insert into dragon_secret (name,value) VALUES(?,?)", KEY, strKey);

        return strKey;
    }

    private Record getRecord(Long recId) {
        Connection conn = null;
        Record rec = null;

        try {
            conn = DbHelper.getConn();
            Statement st = conn.createStatement();
            ResultSet rs = st.executeQuery("select * from dragon_record where id = " + recId);

            if (rs.next()) {
                rec = new Record();
                rec.setId(recId);
                rec.setVeto(rs.getBoolean("veto"));
                rec.setResid(rs.getLong("res_id"));
                rec.setGoTime(rs.getLong("go_time"));
                rec.setgId(rs.getLong("g_id"));
            }

            return rec;
        } catch (Exception e) {
            logger.error(e.getMessage());
            return null;
        } finally {
            DbHelper.closeConn(conn);
        }
    }

    public Restaurant getRestaurant(Pair<String, Object> p) {
        Connection conn = null;
        Restaurant ret = null;

        try {
            conn = DbHelper.getConn();
            PreparedStatement st = conn.prepareStatement("select * from dragon_restaurant where " + p.getLeft() + "= ?");
            DbHelper.setParameters(st, p.getRight());
            ResultSet rs = st.executeQuery();

            if (rs.next()) {
                ret = new Restaurant(rs.getString("name"), rs.getString("link"), rs.getLong("factor"), rs.getLong("id")
                        , rs.getString("alias"), rs.getString("category"));
            }

            return ret;
        } catch (Exception e) {
            logger.error(e.getMessage());
            return null;
        } finally {
            DbHelper.closeConn(conn);
        }
    }

    private Boolean saveVote(Vote r, Connection conn) {

        String mail = r.getEmail();
        Long rid = r.getRecId();
        int res = r.getResult().ordinal();

        boolean reuse = conn != null;//Remember to close connection outside

        try {
            if (!reuse) {
                conn = DbHelper.getConn();
            }

            Object obj = DbHelper.runWithSingleResult2(conn, "select vote from dragon_vote where email = ? and rec_id =?", mail, rid);
            if (obj != null) {
                if (res != (Integer) obj) {
                    DbHelper.runUpdate2(conn, "update dragon_vote set vote=? where email=? and rec_id=?", res, mail, rid);
                } else {
                    return false;
                }
            } else {
                DbHelper.runUpdate2(conn, "insert into dragon_vote (rec_id,vote,email) VALUES(?,?,?)", rid, res, mail);
            }
        } catch (Exception e) {
            logger.error("", e);
        } finally {
            if (!reuse) {
                DbHelper.closeConn(conn);
            }
        }

        return true;
    }

    public List<String> getMails(Long gid) {
        List<String> mails = DbHelper.getFirstColumnList(null, "select u.email from dragon_user u, dragon_group g, dragon_group_user gu where gu.u_id=u.id and gu.g_id=g.id and g.id=?", gid);
        return mails;
    }

    private long getWeight(Map<String, Stat> ss, Restaurant r){
        String name = r.getName();
        if(ss.get(name) != null){
            return r.getWeight() * ss.get(name).getPosScore();
        }
        return r.getWeight() * BASE;
    }

    static class ValueComparator implements Comparator<String> {

        Map<String, Stat> base;
        public ValueComparator(Map<String, Stat> base) {
            this.base = base;
        }

        public int compare(String a, String b) {
            if(base.get(a) == null || base.get(b) == null){
                return 1;
            }
            int ret = base.get(b).getVisited() - base.get(a).getVisited();
            if(ret == 0) {
                return a.compareTo(b);
            }
            return ret;
        }
    }
}