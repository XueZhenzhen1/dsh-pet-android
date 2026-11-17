package local.dsh.pet;

/**
 * 本机演出的动作库。
 *
 * 电脑上那 200 多个动作是 webm（VP9 带 alpha）素材，安卓这边解不了——
 * 这不是配置问题，是解码能力问题：MediaCodec 不输出 alpha 通道，
 * 硬塞进去只会得到一块黑底方块。所以手机上换一条路：
 *
 *   把「动作名」翻成 16 种程序化演出（挤、弹、转、摆、抖、点、躬、跑、嚼、困、涨、晃、爆、翻、晕），
 *   同时把同一条指令发给电脑，让电脑那只播原版。
 *
 * 于是用户点「悠闲哼歌」时看到的是：手机上的鱼球真的开始摇，
 * 电脑上的鱼真的在唱——两边都不是「点了没反应」。
 *
 * 关键词表按「先具体后笼统」排，命中即返回；完全不认识的动作用 POP（轻轻弹一下），
 * 保证任何名字都有反馈。
 */
public final class PetAction {

    public static final int NONE = 0;
    public static final int HOP = 1;
    public static final int SQUASH = 2;
    public static final int STRETCH = 3;
    public static final int SPIN = 4;
    public static final int SWAY = 5;
    public static final int SHAKE = 6;
    public static final int NOD = 7;
    public static final int BOW = 8;
    public static final int RUN = 9;
    public static final int CHEW = 10;
    public static final int DOZE = 11;
    public static final int GROW = 12;
    public static final int WOBBLE = 13;
    public static final int POP = 14;
    public static final int FLIP = 15;
    public static final int DIZZY = 16;

    /** 关键词 → 演出。顺序有意义：越靠前越优先。 */
    private static final Object[][] RULES = {
            {"吃", CHEW}, {"饭", CHEW}, {"零食", CHEW}, {"火锅", CHEW}, {"西瓜", CHEW},
            {"冰淇淋", CHEW}, {"糖", CHEW}, {"蟹", CHEW}, {"面", CHEW}, {"餐", CHEW},
            {"嚼", CHEW}, {"喝", CHEW}, {"偷吃", CHEW},

            {"睡", DOZE}, {"小憩", DOZE}, {"沉眠", DOZE}, {"哈欠", DOZE}, {"打盹", DOZE},
            {"犯困", DOZE}, {"赖床", DOZE},

            {"转", SPIN}, {"360", SPIN}, {"圈", SPIN},

            {"跑", RUN}, {"冲", RUN}, {"逃", RUN}, {"追", RUN}, {"溜达", RUN},

            {"舞", SWAY}, {"摇摆", SWAY}, {"哼歌", SWAY}, {"节奏", SWAY}, {"摇", SWAY},
            {"律动", SWAY}, {"唱", SWAY},

            {"生气", SHAKE}, {"气急", SHAKE}, {"暴躁", SHAKE}, {"急", SHAKE},
            {"吓", SHAKE}, {"惊讶", SHAKE}, {"抖", SHAKE}, {"拒绝", SHAKE},
            {"摇头", SHAKE}, {"吵架", SHAKE},

            {"伸懒腰", STRETCH}, {"伸展", STRETCH}, {"拉伸", STRETCH}, {"抬头", STRETCH},
            {"长高", STRETCH},

            {"鞠躬", BOW}, {"礼仪", BOW}, {"屈膝", BOW}, {"道谢", BOW}, {"点头", BOW},
            {"行礼", BOW}, {"欠身", BOW},

            {"跳", HOP}, {"跃", HOP}, {"蹦", HOP}, {"抓", HOP}, {"够", HOP},

            {"现世", GROW}, {"超大", GROW}, {"变身", GROW}, {"长大", GROW}, {"膨胀", GROW},

            {"翻", FLIP}, {"转身", FLIP}, {"回头", FLIP},

            {"晕", DIZZY}, {"眩", DIZZY}, {"螺旋", DIZZY},

            {"摔", WOBBLE}, {"倒", WOBBLE}, {"晃", WOBBLE}, {"醉", WOBBLE}, {"站不稳", WOBBLE},

            {"魔方", NOD}, {"玩具", NOD}, {"敲", NOD}, {"水枪", NOD}, {"泡泡", NOD},
            {"小提琴", NOD}, {"弹", NOD}, {"画", NOD}, {"看", NOD}, {"想", NOD},
    };

    private PetAction() {
    }

    /** 动作名 → 本机演出。永远返回一个可播的值（最差是 POP）。 */
    public static int presetFor(String name) {
        if (name == null || name.isEmpty()) return POP;
        for (Object[] r : RULES) {
            if (name.contains((String) r[0])) return (Integer) r[1];
        }
        return POP;
    }

    /** 不同演出的节奏不一样：转圈要久一点，抖一下要快。 */
    public static int durationFor(int preset) {
        switch (preset) {
            case SPIN:
            case DIZZY:
                return 1250;
            case RUN:
            case SWAY:
                return 1500;
            case CHEW:
            case DOZE:
                return 1700;
            case BOW:
            case WOBBLE:
                return 1100;
            case GROW:
            case STRETCH:
                return 900;
            case FLIP:
                return 800;
            default:
                return 720;
        }
    }

    /** 这个名字在本机上会演成什么，写在气泡里给用户看。 */
    public static String label(int preset) {
        switch (preset) {
            case HOP: return "蹦了一下";
            case SQUASH: return "被压扁";
            case STRETCH: return "伸了个懒腰";
            case SPIN: return "原地转了个圈";
            case SWAY: return "晃起来了";
            case SHAKE: return "抖个不停";
            case NOD: return "点点头";
            case BOW: return "行了个礼";
            case RUN: return "跑了两步";
            case CHEW: return "嚼嚼嚼";
            case DOZE: return "困了";
            case GROW: return "涨大了";
            case WOBBLE: return "站不稳";
            case FLIP: return "翻了个身";
            case DIZZY: return "转晕了";
            default: return "动了一下";
        }
    }
}
