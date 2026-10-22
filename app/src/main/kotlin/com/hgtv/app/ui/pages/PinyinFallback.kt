package com.hgtv.app.ui.pages

/**
 * 本地全拼逐字候选（SPEC §3.2 拼音候选行）：联想接口不可用时的输入兜底。
 *
 * 音节集与候选字表合一：表键 = 合法全拼音节（v 代 ü，lv/nv/lve/nve），
 * 表值 = 该音节的高频字（偏短剧标题词表），首字即前缀聚合时的代表字。
 */
object PinyinFallback {

    private val CHAR_MAP: LinkedHashMap<String, String> = run {
        val flat = arrayOf(
            "a", "啊阿", "ai", "爱哀艾碍", "an", "安暗案鞍", "ang", "昂肮", "ao", "傲奥熬袄懊",
            "ba", "霸爸吧八扒", "bai", "百白拜败摆", "ban", "半伴版班搬办", "bang", "邦帮绑榜棒",
            "bao", "宝保报爆薄苞", "bei", "北贝备被悲杯辈", "ben", "奔本笨", "beng", "蹦崩绷",
            "bi", "必比避毕逼鼻碧", "bian", "边变便遍编辨鞭", "biao", "彪表标飙", "bie", "别憋瘪",
            "bin", "宾滨彬斌", "bing", "兵冰并病柄丙", "bo", "博薄波拨伯泊", "bu", "不布步部补",
            "ca", "擦", "cai", "才财材彩菜猜裁", "can", "惨灿残餐", "cang", "苍仓舱藏",
            "cao", "草糙操槽", "ce", "策册侧测厕", "cen", "参岑", "ceng", "层曾蹭",
            "cha", "差查茶插刹察", "chai", "拆柴", "chan", "产缠阐颤禅馋", "chang", "长常场唱昌倡",
            "chao", "超朝吵潮抄", "che", "车扯撤彻", "chen", "晨臣沉尘陈衬趁", "cheng", "成城程承诚乘惩呈",
            "chi", "吃迟持池赤翅耻痴", "chong", "冲重崇充宠", "chou", "仇抽愁丑筹绸稠",
            "chu", "出初除楚厨储触处", "chua", "欻", "chuai", "揣踹", "chuan", "穿传川船串",
            "chuang", "创窗床闯", "chui", "吹垂锤槌", "chun", "春纯唇蠢", "chuo", "戳辍绰",
            "ci", "次此辞慈词刺赐", "cong", "从聪匆葱", "cou", "凑", "cu", "醋促簇",
            "cuan", "窜篡蹿", "cui", "催脆翠萃摧", "cun", "存村寸", "cuo", "错措挫搓",
            "da", "大打达答搭", "dai", "代待戴带呆贷", "dan", "单胆淡蛋弹旦诞",
            "dang", "当党荡档", "dao", "到道岛刀盗导倒", "de", "的得德",
            "dei", "得", "den", "扽", "deng", "等灯登邓凳", "di", "帝第敌底弟递低滴迪",
            "dia", "嗲", "dian", "点殿电店颠甸", "diao", "钓雕调吊刁", "die", "跌叠碟爹蝶",
            "ding", "顶定丁钉鼎订", "diu", "丢", "dong", "东冬动懂董冻栋", "dou", "都斗豆抖逗兜",
            "du", "毒读堵度独赌杜督", "duan", "断端短段", "dui", "对队堆兑", "dun", "顿盾吨蹲钝",
            "duo", "多夺朵躲舵堕", "e", "恶饿鹅额厄", "ei", "诶", "en", "恩",
            "er", "而二儿耳尔", "fa", "发罚法伐乏", "fan", "反返犯翻凡烦繁范泛",
            "fang", "方放房防访仿纺", "fei", "非飞费肥废匪菲", "fen", "分份奋愤粉纷",
            "feng", "风封丰逢锋峰疯奉凤", "fo", "佛", "fou", "否", "fu", "父夫福富付服府复负附妇副傅腹",
            "ga", "嘎", "gai", "改盖丐概", "gan", "敢感赶甘干肝", "gang", "港刚钢岗纲",
            "gao", "高告搞糕稿", "ge", "个哥歌格各革隔葛", "gei", "给", "gen", "根跟",
            "geng", "更耕耿", "gong", "公宫工功攻共贡供", "gou", "狗够构购勾沟",
            "gu", "古故顾固谷骨鼓姑", "gua", "挂瓜刮寡", "guai", "怪拐乖", "guan", "关管官观冠馆惯贯罐",
            "guang", "光广逛", "gui", "贵归鬼桂规柜跪", "gun", "滚棍", "guo", "国过果锅郭裹",
            "ha", "哈", "hai", "海还害孩嗨", "han", "汉含寒韩喊汗翰憾",
            "hang", "行航杭巷", "hao", "好号豪毫浩耗", "he", "和何合河喝贺赫",
            "hei", "黑嘿", "hen", "很恨狠痕", "heng", "横衡恒哼", "hong", "红洪宏虹鸿烘哄",
            "hou", "后厚侯喉吼候", "hu", "湖虎呼护户忽胡壶狐糊", "hua", "花话华化划画",
            "huai", "坏怀淮", "huan", "还换欢幻患环唤缓焕", "huang", "黄皇荒慌晃谎煌凰",
            "hui", "回会灰辉汇慧惠毁悔徽", "hun", "婚魂混昏浑", "huo", "火活或货获伙惑祸",
            "ji", "记级极己季集机基技击急寄吉纪计继籍祭", "jia", "家佳加假价嫁嘉甲驾",
            "jian", "见间建剑贱尖监兼艰简检减剪键健荐", "jiang", "将江讲降姜疆匠酱蒋奖",
            "jiao", "叫教交骄娇搅焦蕉角脚缴", "jie", "姐界接结局揭街劫杰洁捷截戒借介届",
            "jin", "进金近今紧禁尽仅斤津锦", "jing", "京惊经精景警静竞净镜井敬境径晶",
            "jiong", "窘炯", "jiu", "就久九酒救旧纠究", "ju", "局举巨句聚拒居剧具俱惧距",
            "juan", "卷眷娟圈倦", "jue", "觉绝决爵倔掘", "jun", "军均君俊峻",
            "ka", "卡咖喀", "kai", "开凯慨揩", "kan", "看刊砍堪坎", "kang", "抗康慷糠扛",
            "kao", "靠考烤拷", "ke", "可课克科客刻壳渴柯颗", "kei", "剋", "ken", "肯啃恳垦",
            "keng", "坑", "kong", "空控恐孔", "kou", "口扣寇抠", "ku", "苦库哭酷枯裤",
            "kua", "跨垮夸挎", "kuai", "快块筷", "kuan", "宽款", "kuang", "狂矿框况旷",
            "kui", "亏愧溃魁葵窥", "kun", "困昆捆坤", "kuo", "阔扩括",
            "la", "拉啦辣蜡腊", "lai", "来赖莱", "lan", "蓝兰栏烂拦懒览", "lang", "狼浪郎朗廊",
            "lao", "老劳牢姥烙涝", "le", "了乐勒", "lei", "类泪雷累蕾垒", "leng", "冷愣棱",
            "li", "力里理利立李离历礼丽例励莉栗", "lia", "俩", "lian", "连恋联脸莲帘怜链炼",
            "liang", "良两亮凉粮梁量辆谅", "liao", "料撩疗聊辽", "lie", "列烈猎裂劣咧",
            "lin", "林临邻淋琳鳞", "ling", "灵领令零玲龄凌陵岭", "liu", "六留流刘柳榴",
            "lo", "咯", "long", "龙隆笼聋垄拢", "lou", "楼漏搂陋", "lu", "路露鲁陆炉录卢虏鹿禄庐",
            "luan", "乱卵", "lun", "论轮伦沦纶", "luo", "落罗洛螺锣逻骆", "lv", "律旅绿吕铝履",
            "lve", "略掠", "ma", "妈马麻骂码吗嘛", "mai", "买卖迈埋麦脉", "man", "满慢漫瞒蔓曼",
            "mang", "忙茫盲芒莽", "mao", "猫帽冒毛矛茂貌", "me", "么", "mei", "美妹媚没每梅媒煤枚玫霉",
            "men", "们门闷", "mi", "米密秘迷蜜眯谜弥", "mian", "面棉免眠缅勉",
            "miao", "秒妙描苗渺瞄", "mie", "灭蔑", "min", "民敏闽悯", "ming", "名明命鸣铭冥",
            "miu", "谬", "mo", "模末莫磨默魔陌漠墨沫", "mou", "某谋牟", "mu", "母木目幕牧慕墓亩暮穆",
            "na", "那拿哪娜纳", "nai", "奶耐乃奈", "nan", "南难男楠", "nang", "囊",
            "nao", "闹恼脑", "ne", "呢讷", "nei", "内", "nen", "嫩", "neng", "能",
            "ni", "你尼泥逆拟腻匿", "nian", "年念粘捻", "niang", "娘酿", "niao", "鸟尿",
            "nie", "捏聂孽镍", "nin", "您", "ning", "宁凝拧柠", "niu", "牛纽扭钮",
            "nong", "农弄浓", "nou", "耨", "nu", "怒奴", "nuan", "暖", "nuo", "诺挪懦",
            "nve", "虐疟", "nv", "女", "o", "哦噢", "ou", "欧偶殴偶",
            "pa", "怕爬帕趴扒", "pai", "派排拍牌", "pan", "判盘叛盼攀", "pang", "旁胖庞",
            "pao", "跑炮泡抛袍刨", "pei", "陪配赔佩培沛", "pen", "盆喷", "peng", "朋捧碰棚蓬鹏膨",
            "pi", "皮批僻劈疲屁脾", "pian", "片篇偏骗", "piao", "票漂飘瓢", "pin", "品贫拼聘频",
            "ping", "平评瓶萍凭屏", "po", "破迫魄婆坡泼颇", "pou", "剖", "pu", "仆普铺扑朴浦葡蒲",
            "qi", "起其七期气奇妻弃齐骑旗棋欺漆汽", "qia", "恰掐卡", "qian", "千钱前签浅欠牵谦乾坤",
            "qiang", "强抢枪墙腔呛", "qiao", "桥悄瞧敲巧俏窍乔", "qie", "切且窃怯妾",
            "qin", "亲琴勤侵秦擒寝", "qing", "清情青轻倾庆请晴擎", "qiong", "穷琼穹",
            "qiu", "求球秋丘囚", "qu", "去取区趣曲趋屈驱渠", "quan", "全权拳泉劝圈犬",
            "que", "却缺确雀鹊", "qun", "群裙", "ran", "然燃染", "rang", "让嚷壤",
            "rao", "绕扰饶", "re", "热惹", "ren", "人任认忍仁刃", "reng", "扔仍",
            "ri", "日", "rong", "容融荣绒熔蓉", "rou", "肉柔揉", "ru", "如入儒乳辱",
            "rua", "挼", "ruan", "软", "rui", "锐瑞蕊睿", "run", "润闰", "ruo", "若弱",
            "sa", "撒洒萨", "sai", "赛塞腮", "san", "三散伞叁", "sang", "桑丧",
            "sao", "扫嫂骚", "se", "色涩瑟", "sen", "森", "seng", "僧", "sha", "杀沙纱傻刹煞",
            "shai", "筛晒", "shan", "山闪善扇衫擅赡", "shang", "上商伤赏尚",
            "shao", "少烧稍勺哨绍", "she", "蛇舍设社射摄涉", "shei", "谁",
            "shen", "神深身什审婶沈申慎甚", "sheng", "生胜声剩升圣绳省",
            "shi", "是时十事世市师诗使始示势视试食石史士誓释饰",
            "shou", "手首收受授售寿瘦兽守", "shu", "书数树属术输束述熟叔鼠署蜀舒疏殊",
            "shua", "刷耍", "shuai", "帅摔甩率", "shuan", "拴栓闩", "shuang", "双爽霜",
            "shui", "水谁税睡", "shun", "顺瞬舜", "shuo", "说硕烁朔",
            "si", "死四思私司丝似斯撕肆寺", "song", "送松宋颂讼耸", "sou", "搜艘嗖",
            "su", "速素苏诉俗宿塑肃酥", "suan", "算酸蒜", "sui", "岁随虽碎穗遂",
            "sun", "孙损笋", "suo", "所锁索缩梭琐", "ta", "他她它塔踏塌",
            "tai", "太台态抬泰苔", "tan", "谈贪弹摊滩坛叹探炭", "tang", "堂糖汤躺烫趟唐塘",
            "tao", "逃桃讨淘陶涛套萄", "te", "特", "teng", "疼腾藤", "ti", "体提题替踢梯惕剃",
            "tian", "天田甜添填", "tiao", "条跳挑调迢", "tie", "铁贴帖", "ting", "听停庭亭挺廷艇",
            "tong", "同通童统痛铜桶筒", "tou", "头偷投透", "tu", "土图途突吐徒屠兔",
            "tuan", "团湍", "tui", "推退腿褪", "tun", "吞屯臀", "tuo", "拖脱托驼驮妥",
            "wa", "挖娃蛙瓦袜", "wai", "外歪", "wan", "完晚万玩碗湾婉挽腕", "wang", "王往网忘望汪旺",
            "wei", "为位未维围唯危伟卫威微委违韦", "wen", "问温文闻稳吻纹", "weng", "翁嗡",
            "wo", "我握窝卧沃", "wu", "五无武物误屋吴伍舞雾乌污悟吾",
            "xi", "西喜系细洗席袭习戏希析吸锡溪惜", "xia", "下夏吓虾峡狭侠瞎",
            "xian", "先现线险鲜限仙贤献县显闲", "xiang", "想相向香象像项享响箱乡祥",
            "xiao", "小笑消削效销晓肖啸萧", "xie", "些写谢协械斜胁鞋携邪卸泄",
            "xin", "心新信芯辛欣薪", "xing", "行星性姓兴醒形幸刑型", "xiong", "兄雄胸凶熊",
            "xiu", "修秀休羞袖绣锈", "xu", "需许续须徐虚序绪蓄", "xuan", "选宣旋玄悬萱",
            "xue", "学雪血穴靴薛", "xun", "寻训迅讯巡逊熏", "ya", "牙雅压呀押鸦崖",
            "yan", "眼烟言严演验沿研盐燕颜艳宴", "yang", "样阳养洋仰扬漾", "yao", "要药摇遥咬腰邀耀钥",
            "ye", "也夜业叶爷野液冶", "yi", "一以已意义易亿艺议衣医依移异遗仪宜疑毅",
            "yin", "因音银引印隐姻饮", "ying", "应影英赢迎营盈鹰颖映", "yo", "哟",
            "yong", "用永涌勇拥庸泳", "you", "有又友由油游右优幽悠忧邮",
            "yu", "与育雨语玉于余欲狱遇域预誉渔愈御", "yuan", "元远员院原圆源愿缘援袁园",
            "yue", "月越约跃乐岳悦阅", "yun", "云运晕韵孕匀", "za", "杂砸咋",
            "zai", "在再载灾栽宰", "zan", "赞暂攒", "zang", "脏葬赃", "zao", "早造遭糟枣灶燥",
            "ze", "则责择泽", "zei", "贼", "zen", "怎", "zeng", "增赠",
            "zha", "炸渣扎眨榨", "zhai", "摘宅窄债寨", "zhan", "战站占展斩沾盏湛",
            "zhang", "长张章丈涨帐账障掌", "zhao", "找着招照朝罩兆", "zhe", "这者折遮哲浙",
            "zhen", "真镇阵震珍针侦枕", "zheng", "正争政征整蒸睁筝证郑", "zhi", "之只知至制质直指值纸治支志致智执职",
            "zhong", "中重众终忠钟种仲", "zhou", "周州洲舟粥轴肘皱", "zhu", "主住注猪珠朱株诸逐竹祝筑",
            "zhua", "抓", "zhuai", "拽", "zhuan", "转专赚砖传", "zhuang", "装壮状庄撞",
            "zhui", "追坠缀锥", "zhun", "准", "zhuo", "捉桌卓琢灼浊", "zi", "子自字资紫姿滋",
            "zong", "总宗纵综棕", "zou", "走奏揍邹", "zu", "足族组祖阻租", "zuan", "钻攥",
            "zui", "最罪醉嘴", "zun", "尊遵", "zuo", "作做坐座左昨",
        )
        LinkedHashMap<String, String>().apply {
            var i = 0
            while (i + 1 < flat.size) {
                put(flat[i], flat[i + 1])
                i += 2
            }
        }
    }

    private val SYLLABLES = CHAR_MAP.keys
    private val TRAILING_ALPHA = Regex("[a-z]+$")

    /** 全拼音节切分（贪心优先长音节+回溯）；含非小写字母或无法完全切分时返回空表。 */
    fun splitSyllables(input: String): List<String> = splitFrom(input, 0, input.length) ?: emptyList()

    /**
     * 末尾待上屏音节：完整切分 → 末音节；末段未完成 → 最长可作音节前缀的截尾；
     * 整串都是未完成前缀 → 整串；非字母/空 → null。上屏 = 去掉 pending 换字。
     */
    fun pending(typed: String): String? {
        val t = typed.lowercase()
        // 逐字上屏是"汉字+新字母"交替的，只分析末尾连续字母段
        val seg = TRAILING_ALPHA.find(t)?.value ?: return null
        splitFrom(seg, 0, seg.length)?.let { return it.last() }
        for (cut in minOf(6, seg.length - 1) downTo 1) {
            val tail = seg.substring(seg.length - cut)
            if (SYLLABLES.none { it.startsWith(tail) }) continue
            if (splitFrom(seg, 0, seg.length - cut) != null) return tail
        }
        return seg
    }

    /**
     * 末音节候选字（逐字上屏用）：完整音节 → 其候选字；
     * 未完成段 → 以其为前缀聚合各音节代表字；否则空。
     */
    fun candidates(typed: String): List<String> {
        val p = pending(typed) ?: return emptyList()
        CHAR_MAP[p]?.let { chars -> return chars.toList().map { c -> c.toString() } }
        return prefixCandidates(p)
    }

    private fun prefixCandidates(prefix: String): List<String> {
        val out = ArrayList<String>()
        for ((syllable, chars) in CHAR_MAP) {
            if (!syllable.startsWith(prefix)) continue
            for (ch in chars) {
                val s = ch.toString()
                if (s !in out) out.add(s)
                if (out.size >= 8) return out
            }
        }
        return out
    }

    /** 回溯切分 s[start,end)；完整切分返回音节列表，否则 null。 */
    private fun splitFrom(s: String, start: Int, end: Int): List<String>? {
        if (start == end) return emptyList()
        for (len in minOf(6, end - start) downTo 1) {
            val seg = s.substring(start, start + len)
            if (seg in SYLLABLES) {
                val rest = splitFrom(s, start + len, end) ?: continue
                return listOf(seg) + rest
            }
        }
        return null
    }
}
