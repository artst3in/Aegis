import numpy as np
import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt

# ----------------------------------------------------------------------
# Expected-cost model for the SOS hold duration t (seconds).
#
#   C(t) = A*exp(-t/tau)        # false-alarm channel  (decreasing in t)
#        + B*t                  # delay channel        (increasing in t)
#        + M*P_int(t)           # attacker-interrupt    (only if touchscreen is the SOLE path)
#
#   tau : accidental-contact survival decay. "99% of accidental touches < 0.5s"
#         => exp(-0.5/tau)=0.01 => tau ~ 0.11; "99.9% < 1.0s" => tau ~ 0.145.
#         Use tau = 0.13 (mid). Heavier real tail only pushes t* UP slightly.
#   A   : lambda_acc * defense_multiplier(0.0096) * C_fp  (cry-wolf cost x rate)
#   B   : delay-cost per second in a real emergency (normalized to 1)
#   M,P_int : catastrophic cost of a DEFEATED sos, weighted by the chance an
#         attacker perceives+grabs during the hold. P_int=0 below reach-floor D.
# ----------------------------------------------------------------------

tau = 0.13
B   = 1.0
A   = 284.0          # set so the FP<->delay BALANCE lands in the ~1s region; t* is DERIVED below
D   = 0.45           # attacker perceive+reach floor (s)
h   = 2.5            # disrupt hazard rate after D (1/s)
M   = 8.0            # weight of a defeated SOS (lambda_em * C_miss) -- catastrophic

t = np.linspace(0.01, 2.5, 4000)

C_fp    = A*np.exp(-t/tau)
C_delay = B*t
P_int   = np.where(t > D, 1.0 - np.exp(-h*(t-D)), 0.0)
C_att   = M*P_int

C_noatt     = C_fp + C_delay
C_withatt   = C_fp + C_delay + C_att

# closed form (no attacker): dC/dt = -(A/tau)exp(-t/tau)+B = 0  =>  t* = tau*ln(A/(B tau))
t_star_cf    = tau*np.log(A/(B*tau))
t_star_noatt = t[np.argmin(C_noatt)]
t_star_att   = t[np.argmin(C_withatt)]

print(f"closed-form t* (no attacker) = {t_star_cf:.3f} s")
print(f"numeric    t* (no attacker) = {t_star_noatt:.3f} s")
print(f"numeric    t* (with attacker)= {t_star_att:.3f} s")
print(f"cost-ratio multiplier needed to move 1.0 -> 1.5 s = {np.exp((1.5-1.0)/tau):.1f}x")
print(f"cost-ratio multiplier needed to move 0.7 -> 1.0 s = {np.exp((1.0-0.7)/tau):.1f}x")

# ---- figure ----
fig, ax = plt.subplots(1, 3, figsize=(18, 5.4))

# Panel A: no attacker -> the corrected case
ax[0].plot(t, C_fp,    "--", color="#c0392b", lw=1.8, label=r"false-alarm  $A e^{-t/\tau}$")
ax[0].plot(t, C_delay, "--", color="#2980b9", lw=1.8, label=r"delay  $B\,t$")
ax[0].plot(t, C_noatt, "-",  color="#000000", lw=2.6, label="total  C(t)")
ax[0].axvline(t_star_noatt, color="#27ae60", lw=2, ls=":")
ax[0].plot([t_star_noatt],[C_noatt.min()],"o",color="#27ae60",ms=9)
ax[0].annotate(f"t* = {t_star_noatt:.2f} s",
               (t_star_noatt, C_noatt.min()), xytext=(t_star_noatt+0.25, C_noatt.min()+1.3),
               color="#27ae60", fontsize=12, fontweight="bold",
               arrowprops=dict(arrowstyle="->", color="#27ae60"))
ax[0].set_title("A.  Touchscreen path (hardware trigger covers attacker)\nFP vs delay only", fontsize=12)
ax[0].set_xlabel("hold duration t (s)"); ax[0].set_ylabel("expected cost (delay-units)")
ax[0].set_ylim(0, 5); ax[0].set_xlim(0, 2.5); ax[0].legend(fontsize=10); ax[0].grid(alpha=0.25)

# Panel B: with attacker term -> what pinned it short before
ax[1].plot(t, C_fp,      "--", color="#c0392b", lw=1.6, label=r"false-alarm")
ax[1].plot(t, C_delay,   "--", color="#2980b9", lw=1.6, label=r"delay")
ax[1].plot(t, C_att,     "--", color="#8e44ad", lw=1.6, label=r"attacker-interrupt  $M\,P_{int}(t)$")
ax[1].plot(t, C_withatt, "-",  color="#000000", lw=2.6, label="total C(t)")
ax[1].axvline(t_star_att, color="#e67e22", lw=2, ls=":")
ax[1].plot([t_star_att],[C_withatt.min()],"o",color="#e67e22",ms=9)
ax[1].annotate(f"t* = {t_star_att:.2f} s",
               (t_star_att, C_withatt.min()), xytext=(t_star_att+0.35, C_withatt.min()+1.3),
               color="#e67e22", fontsize=12, fontweight="bold",
               arrowprops=dict(arrowstyle="->", color="#e67e22"))
ax[1].set_title("B.  If touchscreen were the ONLY path\nattacker term yanks t* left", fontsize=12)
ax[1].set_xlabel("hold duration t (s)"); ax[1].set_ylim(0, 5); ax[1].set_xlim(0, 2.5)
ax[1].legend(fontsize=10); ax[1].grid(alpha=0.25)

# Panel C: log-robustness. t* = tau ln(R), R = A/(B tau)
R = np.logspace(1.5, 6.5, 500)
tstar_R = tau*np.log(R)
ax[2].semilogx(R, tstar_R, "-", color="#000000", lw=2.4)
for tv, col in [(0.7,"#e67e22"), (1.0,"#27ae60"), (1.5,"#c0392b")]:
    Rv = np.exp(tv/tau)
    ax[2].plot([Rv],[tv],"o",color=col,ms=8)
    ax[2].annotate(f"{tv:.1f}s", (Rv, tv), xytext=(Rv*1.4, tv-0.12), color=col, fontsize=11, fontweight="bold")
ax[2].axhspan(0.7, 1.0, color="#27ae60", alpha=0.10)
ax[2].set_title("C.  t* depends only on LOG of cost-asymmetry\n(why the answer is robust)", fontsize=12)
ax[2].set_xlabel(r"cost-asymmetry ratio  $R = A/(B\tau)$  (log scale)")
ax[2].set_ylabel("optimal hold t* (s)"); ax[2].set_ylim(0, 2.2); ax[2].grid(alpha=0.25, which="both")

fig.suptitle("SOS hold-duration optimization  —  C(t)=A e^(-t/tau) + B t [+ M P_int(t)]",
             fontsize=13, fontweight="bold")
fig.tight_layout(rect=[0,0,1,0.95])
fig.savefig("/tmp/sos_hold_optimization.png", dpi=130)
print("saved /tmp/sos_hold_optimization.png")
