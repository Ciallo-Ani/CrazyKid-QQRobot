package cn.crazykid.qqrobot.listener.group.message

import cc.moecraft.icq.event.EventHandler
import cc.moecraft.icq.event.IcqListener
import cc.moecraft.icq.event.events.message.EventGroupMessage
import cc.moecraft.icq.sender.message.MessageBuilder
import cc.moecraft.icq.sender.message.components.ComponentReply
import cc.moecraft.icq.user.GroupUser
import cn.crazykid.qqrobot.dao.intf.ArcadeDao
import cn.crazykid.qqrobot.entity.Arcade
import cn.crazykid.qqrobot.listener.group.message.GroupMessageCounterListener.Companion.getMessageCountInGroup
import cn.crazykid.qqrobot.util.ArcadeQueueCardUtil
import cn.hutool.core.date.DateUtil
import cn.hutool.core.lang.Console
import cn.hutool.core.thread.ThreadUtil
import cn.hutool.core.util.ReUtil
import cn.hutool.db.nosql.redis.RedisDS
import com.alibaba.fastjson.JSON
import lombok.SneakyThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import redis.clients.jedis.Jedis
import java.util.*
import java.util.regex.Pattern
import kotlin.math.ceil
import kotlin.math.floor

/**
 * maimai机厅几卡
 *
 * @author CrazyKid (i@crazykid.moe)
 * @since 2021/3/6 17:00
 */
@Component
class GroupMessageMaimaiQueueCardListener : IcqListener() {
    @Autowired
    private lateinit var arcadeDao: ArcadeDao

    @Value("\${arcadeCardCounter.enable:false}")
    private var isEnable: Boolean = false

    private val jedis: Jedis = RedisDS.create().jedis

    private val selectCardNumPattern = Pattern.compile("^(.*?)(现在)?(几|多少)([个张位])?([卡人神爷爹])[?？]?$")
    private val selectCardNumPattern2 = Pattern.compile("^(.*)[jJ几][kK卡]?$")
    private val operateCardNumPattern =
        Pattern.compile("^(.*)([+＋加\\-－减=＝])(\\d{1,6}|[一两俩二三仨四五六七八九十+＋\\-－])([个张位])?([卡人神爷爹])?[\\s+]*(\\[CQ:at,qq=(\\d{1,12})\\])?$")
    private val whoPattern = Pattern.compile("^(.*)有谁[?？]?$")
    private val wherePattern = Pattern.compile("^(.*)在哪[?？]?$")

    companion object {
        private const val CACHE_NAME = "ArcadeCardQueue"
        private const val HISTORY_CACHE_NAME = "ArcadeCardQueueOperateHistory"
    }

    init {
        jedis.select(4)
    }

    @SneakyThrows
    private fun getArcadeList(groupNumber: Long, isReload: Boolean): List<Arcade> {
        Console.log("获取机厅列表...")
        val arcadeList: List<Arcade>
        val json: String? = jedis[CACHE_NAME]
        if (json.isNullOrBlank()) {
            Console.log("从db获取..")
            arcadeList = arcadeDao.selectEnableArcades()
            for (arcade in arcadeList) {
                if (!isReload) {
                    arcade.cardNum = 0
                    arcade.cardUpdateBy = null
                    arcade.cardUpdateTime = null
                }
                Console.log("载入机厅 {}", arcade.name)
            }
            jedis.setex(CACHE_NAME, cacheExpireSecond, JSON.toJSONString(arcadeList))
        } else {
            Console.log("从redis中获取..")
            arcadeList = JSON.parseArray(json, Arcade::class.java)
        }

        // 排序按群号置顶
        // https://www.cnblogs.com/firstdream/p/7204067.html
        arcadeList.sortWith(
            Comparator.comparing(
                { arcade: Arcade -> getArcadeGroupNumber(arcade) }) { x: List<Long>, y: List<Long> ->
                if (x.isEmpty() && y.isEmpty()) {
                    return@comparing 0
                }
                if (x.isEmpty()) {
                    return@comparing -1
                }
                if (y.isEmpty()) {
                    return@comparing 1
                }
                val xnumber = x[0]
                val ynumber = y[0]
                if (xnumber == groupNumber && ynumber != groupNumber) {
                    return@comparing -1
                }
                if (xnumber != groupNumber && ynumber == groupNumber) {
                    return@comparing 1
                }
                0
            })
        return arcadeList
    }

    private fun saveHistory(arcadeName: String, messageParam: String) {
        val json: String? = jedis.hget(HISTORY_CACHE_NAME, arcadeName)
        val historyList: MutableList<String> = if (json.isNullOrBlank()) {
            JSON.parseArray(json, String::class.java)
        } else {
            mutableListOf()
        }
        var message = messageParam
        message = DateUtil.format(Date(), "HH:mm:ss") + " " + message
        historyList.add(message)
        jedis.hset(HISTORY_CACHE_NAME, arcadeName, JSON.toJSONString(historyList))
        jedis.expire(HISTORY_CACHE_NAME, cacheExpireSecond)
    }

    private fun getHistory(arcadeName: String): String {
        val json: String? = jedis.hget(HISTORY_CACHE_NAME, arcadeName)
        val m = MessageBuilder()
        if (json.isNullOrBlank()) {
            m.add(arcadeName).add(" 暂无加减卡记录。")
        } else {
            val historyList = JSON.parseArray(json, String::class.java)
            m.add(arcadeName).add(" 历史记录: ")
            for (s in historyList) {
                m.newLine().add(s)
            }
        }
        return m.toString()
    }

    private fun saveArcadeList(arcadeList: List<Arcade>) {
        jedis.setex(CACHE_NAME, cacheExpireSecond, JSON.toJSONString(arcadeList))
    }

    // 距离第二天早晨5点的秒数
    private val cacheExpireSecond: Int
        get() {
            // 距离第二天早晨5点的秒数
            val calendar = Calendar.getInstance()
            if (calendar[Calendar.HOUR_OF_DAY] >= 5) {
                calendar.add(Calendar.DAY_OF_MONTH, 1)
            }
            calendar[Calendar.HOUR_OF_DAY] = 5
            calendar[Calendar.MINUTE] = 0
            calendar[Calendar.SECOND] = 0
            return Math.toIntExact((calendar.timeInMillis - System.currentTimeMillis()) / 1000)
        }

    @EventHandler
    fun event(event: EventGroupMessage) {
        if (!isEnable) {
            return
        }
        val message = event.getMessage().trim()
        if ("j" == message || "几卡" == message || "几人" == message || "几爷" == message || "几神" == message || "查卡" == message) {
            var hasArcade = false
            val m = MessageBuilder()
            m.add(ComponentReply(event.messageId))
            for (arcade in getArcadeList(event.groupId, false)) {
                if (getArcadeGroupNumber(arcade).contains(event.groupId)) {
                    hasArcade = true
                    m.add(arcade.name).add(": ").add(arcade.cardNum).add("卡")
                    if (arcade.machineNum > 1 && arcade.cardNum != 0) {
                        val average = arcade.cardNum.toFloat() / arcade.machineNum
                        val floor = floor(average.toDouble()).toInt()
                        val ceil = ceil(average.toDouble()).toInt()
                        if (floor == ceil) {
                            m.add(",机均").add(floor)
                        } else {
                            m.add(",机均").add(floor).add("-").add(ceil)
                        }
                    }
                    if (arcade.updateTime == null) {
                        m.add("(今日未更新) ")
                    } else {
                        m.add("(").add(DateUtil.format(arcade.updateTime, "HH:mm:ss")).add(") ")
                    }
                    m.newLine()
                }
            }
            m.add("其它本BOT统计的机厅卡数可至 https://bot.crazykid.cn/ 查看")
            if (hasArcade) {
                sendGroupMsg(event, event.groupId, m.toString(), 1000)
            }
            return
        }
        if ("机厅列表" == message) {
            var hasArcade = false
            val m = MessageBuilder()
            m.add(ComponentReply(event.messageId)).add("机厅名称及别名如下:").newLine()
            for (arcade in getArcadeList(event.groupId, false)) {
                if (getArcadeGroupNumber(arcade).contains(event.groupId)) {
                    hasArcade = true
                    m.add(arcade.name).add(": ").add(java.lang.String.join("、", getArcadeAlias(arcade)))
                    m.newLine()
                }
            }
            if (hasArcade) {
                sendGroupMsg(event, event.groupId, m.toString(), 1000)
            }
            return
        }

        // 查卡
        var arcadeName: String? = ReUtil.get(selectCardNumPattern, message, 1)
        var cardUnit = "卡"
        if (arcadeName.isNullOrBlank()) {
            arcadeName = ReUtil.get(selectCardNumPattern2, message, 1)
        } else {
            val sb = StringBuilder()
            if (ReUtil.get(selectCardNumPattern, message, 4) != null) {
                sb.append(ReUtil.get(selectCardNumPattern, message, 4))
            }
            if (ReUtil.get(selectCardNumPattern, message, 5) != null) {
                sb.append(ReUtil.get(selectCardNumPattern, message, 5))
            }
            if (sb.isNotEmpty()) {
                cardUnit = sb.toString()
            }
        }
        if (arcadeName?.isNotEmpty() == true) {
            val arcadeList = getArcadeList(event.groupId, false)
            val m = MessageBuilder()
            m.add(ComponentReply(event.messageId))
            for (arcade in arcadeList) {
                if ((arcade.name == arcadeName || getArcadeAlias(arcade).contains(arcadeName)) && (getArcadeGroupNumber(
                        arcade
                    ).contains(event.groupId) || getArcadeGroupNumber(arcade).isEmpty())
                ) {
                    m.add(arcade.name).add("现在").add(arcade.cardNum).add(cardUnit)
                    if (arcade.machineNum!! > 1 && arcade.cardNum != 0) {
                        val average = arcade.cardNum.toFloat() / arcade.machineNum
                        val floor = floor(average.toDouble()).toInt()
                        val ceil = ceil(average.toDouble()).toInt()
                        if (floor == ceil) {
                            m.add(", 机均").add(floor).add(cardUnit).add("。").newLine()
                        } else {
                            m.add(", 机均").add(floor).add("-").add(ceil).add(cardUnit).add("。").newLine()
                        }
                    } else {
                        m.add("。").newLine()
                    }
                    if (arcade.updateTime == null) {
                        m.add("今日未更新。")
                    } else {
                        m.add("最后由 ").add(arcade.updateBy).add(" 更新于 ")
                            .add(DateUtil.format(arcade.updateTime, "HH:mm:ss")).add("。")
                    }
                    m.newLine().add("加减" + cardUnit + "数请发送\"" + arcadeName + "++\"或\"" + arcadeName + "--\"")
                    sendGroupMsg(event, event.groupId, m.toString(), 1000)
                    return
                }
            }
            /*
            m.add("没这机厅! ").newLine().add("你群现在支持的机厅如下: ");
            for (Arcade arcade : arcadeList) {
                if (this.getArcadeGroupNumber(arcade).contains(event.getGroupId())) {
                    m.newLine()
                            .add(arcade.getName()).add(", 别名: ").add(String.join("、", this.getArcadeAlias(arcade)));
                }
            }
            m.newLine()
                    .add("查询本bot已接管的机厅排卡实况: http://bot.crazykid.cn/#/cardQueue");
            this.sendGroupMsg(event, event.getGroupId(), m.toString(), 1000);
             */return
        }

        // 操作卡
        val reGroup = ReUtil.getAllGroups(operateCardNumPattern, message)
        if (reGroup.isNotEmpty()) {
            val m = MessageBuilder()
            arcadeName = reGroup[1]
            val operate = reGroup[2]
            val numberStr = reGroup[3]
            val sb = StringBuilder()
            if (reGroup[4] != null) {
                sb.append(reGroup[4])
            }
            if (reGroup[5] != null) {
                sb.append(reGroup[5])
            }
            if (sb.isNotEmpty()) {
                cardUnit = sb.toString()
            }
            val helpQQ = reGroup[7]
            var helpGroupUser: GroupUser? = null
            if (helpQQ != null && event.senderId.toString() != helpQQ && event.getSelfId().toString() != helpQQ) {
                helpGroupUser = event.getGroupUser(helpQQ.toLong())
            }
            val number = numberStrToInt(numberStr)
            if (number <= 0) {
                return
            }
            val arcadeList = getArcadeList(event.groupId, false)
            m.add(ComponentReply(event.messageId))
            for (arcade in arcadeList) {
                // 加卡类型 0/未知 1/加 2/减 3/设置
                var operateType = 0
                if ((arcade.name == arcadeName || getArcadeAlias(arcade).contains(arcadeName)) && (getArcadeGroupNumber(
                        arcade
                    ).contains(event.groupId) || getArcadeGroupNumber(arcade).isEmpty())
                ) {
                    if (number > 30) {
                        m.add("一次不能操作多于30张卡")
                        sendGroupMsg(event, event.groupId, m.toString(), 1000)
                        return
                    }
                    val operator =
                        if (event.groupSender.info.card.isNotEmpty()) event.groupSender.info.card else event.groupSender.info.nickname
                    when (operate) {
                        "=", "＝" -> {
                            operateType = 3
                            setCard(arcade, number, operator)
                        }
                        "+", "＋", "加" -> {
                            operateType = 1
                            addCard(arcade, number, operator)
                        }
                        else -> {
                            if (arcade.cardNum < number) {
                                m.add(arcade.name).add("现在").add(arcade.cardNum).add("卡, 不够减!")
                                sendGroupMsg(event, event.groupId, m.toString(), 1000)
                                return
                            }
                            operateType = 2
                            addCard(arcade, number * -1, operator)
                        }
                    }
                    saveArcadeList(arcadeList)

                    // 牛bot兼容卡数修正
                    if (event.groupId == 437189122L && (reGroup[5] == null || "卡" != reGroup[5])) {
                        if (operateType == 1 || operateType == 2) {
                            val mb = MessageBuilder()
                            mb.add(arcadeName).add(if (operateType == 1) "+" else "-").add(number).add("卡")
                            event.httpApi.sendGroupMsg(event.groupId, mb.toString())
                        }
                    }
                    m.add("更新成功! ")
                    if (operateType == 1 || operateType == 2) {
                        if (helpGroupUser != null) {
                            m.add("为 ")
                                .add(if (helpGroupUser.info.card.isNullOrBlank()) helpGroupUser.info.nickname else helpGroupUser.info.card)
                                .add(if (operateType == 1) " 加了" else " 减了").add(number).add("卡")
                            saveHistory(
                                arcade.name!!,
                                operator + " 为 " + (if (helpGroupUser.info.card.isNullOrBlank()) helpGroupUser.info.nickname else helpGroupUser.info.card) + (if (operateType == 1) " 加了" else " 减了") + number + "卡 (" + arcade.cardNum + ")"
                            )
                        } else {
                            saveHistory(
                                arcade.name!!,
                                operator + (if (operateType == 1) " 加了" else " 减了") + number + "卡 (" + arcade.cardNum + ")"
                            )
                        }
                    } else if (operateType == 3) {
                        saveHistory(arcade.name!!, "$operator 设置卡数为$number")
                    }
                    m.newLine().add(arcade.name).add("现在").add(arcade.cardNum).add(cardUnit)
                    if (arcade.machineNum > 1 && arcade.cardNum != 0) {
                        val average = arcade.cardNum.toFloat() / arcade.machineNum
                        val floor = floor(average.toDouble()).toInt()
                        val ceil = ceil(average.toDouble()).toInt()
                        if (floor == ceil) {
                            m.add(", 机均").add(floor).add(cardUnit).add("。").newLine()
                        } else {
                            m.add(", 机均").add(floor).add("-").add(ceil).add(cardUnit).newLine()
                        }
                    } else {
                        m.newLine()
                    }
                    sendGroupMsg(event, event.groupId, m.toString(), 1000)
                    return
                }
            }
            //m.add("没这机厅");
            //this.sendGroupMsg(event, event.getGroupId(), m.toString());
        }

        // 查地址
        arcadeName = ReUtil.get(wherePattern, message, 1)
        if (arcadeName != null) {
            val arcadeList = getArcadeList(event.groupId, false)
            val m = MessageBuilder()
            m.add(ComponentReply(event.messageId))
            for (arcade in arcadeList) {
                if ((arcade.name == arcadeName || getArcadeAlias(arcade).contains(arcadeName)) && (getArcadeGroupNumber(
                        arcade
                    ).contains(event.groupId) || getArcadeGroupNumber(arcade).isEmpty())
                ) {
                    m.add(arcade.address)
                    sendGroupMsg(event, event.groupId, m.toString(), 1000)
                    return
                }
            }
            //m.add("没这机厅");
            //this.sendGroupMsg(event, event.getGroupId(), m.toString());
        }

        // 查历史
        arcadeName = ReUtil.get(whoPattern, message, 1)
        if (arcadeName != null) {
            val arcadeList = getArcadeList(event.groupId, false)
            val m = MessageBuilder()
            m.add(ComponentReply(event.messageId))
            for (arcade in arcadeList) {
                if ((arcade.name == arcadeName || getArcadeAlias(arcade).contains(arcadeName)) && (getArcadeGroupNumber(
                        arcade
                    ).contains(event.groupId) || getArcadeGroupNumber(arcade).isEmpty())
                ) {
                    sendGroupMsg(event, event.groupId, getHistory(arcade.name!!), 2000)
                    return
                }
            }
            //m.add("没这机厅");
            //this.sendGroupMsg(event, event.getGroupId(), m.toString());
        }
    }

    private fun numberStrToInt(numberStr: String?): Int {
        if (numberStr == null) {
            return 0
        }
        when (numberStr) {
            "一", "+", "＋", "-", "－" -> return 1
            "二", "两", "俩" -> return 2
            "三", "仨" -> return 3
            "四" -> return 4
            "五" -> return 5
            "六" -> return 6
            "七" -> return 7
            "八" -> return 8
            "九" -> return 9
            "十" -> return 10
            else -> {}
        }
        return try {
            numberStr.toInt()
        } catch (e: Exception) {
            -1
        }
    }

    private fun sendGroupMsg(event: EventGroupMessage, groupId: Long, message: String, sleepMillis: Long) {
        if (message.isBlank()) {
            return
        }
        GroupMessageCounterListener.GROUP_MAP.clear()
        val r = Runnable {
            var sendMsg = true
            if (437189122L == groupId) {
                ThreadUtil.safeSleep(sleepMillis)
                sendMsg = getMessageCountInGroup(groupId, 1875425568L) == 0 // 牛意思bot
            } else if (486156320L == groupId) {
                ThreadUtil.safeSleep(sleepMillis)
                sendMsg = getMessageCountInGroup(groupId, 2568226265L) == 0 // 占星铃铃
            }
            if (sendMsg) {
                event.httpApi.sendGroupMsg(groupId, message)
            }
        }
        ThreadUtil.execute(r)
    }

    private fun addCard(arcade: Arcade, num: Int, updateBy: String) {
        ArcadeQueueCardUtil.addCard(arcade, num, updateBy)
        updateDatabase(arcade, arcade.cardNum, updateBy)
    }

    private fun setCard(arcade: Arcade, num: Int, updateBy: String) {
        ArcadeQueueCardUtil.setCard(arcade, num, updateBy)
        updateDatabase(arcade, num, updateBy)
    }

    private fun getArcadeAlias(arcade: Arcade): List<String> {
        return ArcadeQueueCardUtil.getArcadeAlias(arcade)
    }

    private fun getArcadeGroupNumber(arcade: Arcade): List<Long> {
        return ArcadeQueueCardUtil.getArcadeGroupNumber(arcade)
    }

    private fun updateDatabase(arcade: Arcade, num: Int, updateBy: String) {
        ThreadUtil.execute {
            val update = Arcade()
            update.id = arcade.id
            update.cardNum = num
            update.cardUpdateBy = updateBy
            update.cardUpdateTime = Date()
            arcadeDao.updateById(update)
        }
    }
}
