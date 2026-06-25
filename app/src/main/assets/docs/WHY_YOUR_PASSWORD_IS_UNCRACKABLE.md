# Why your password is uncrackable

## The short version

A 12-character password like "mymomlovesme" would take every
computer on Earth 150 million years to crack. Not because the
password is clever. Because Aegis makes guessing expensive.

## How password cracking works

An attacker who steals your backup file tries passwords one by
one until one works. The speed depends on how fast they can
test each guess.

Without protection, a modern graphics card tests 10 billion
passwords per second. A 12-character lowercase password has
95 trillion combinations. One graphics card cracks it in
110 days. A thousand graphics cards crack it in 2.5 hours.

## What Aegis does differently

Aegis uses Argon2id to process your password. Argon2id
deliberately wastes time and memory on every guess. Instead of
10 billion attempts per second, an attacker gets 20.

Not 20 million. Not 20 thousand. Twenty.

## The math

A 12-character lowercase password: 95,428,956,661,682,176
possible combinations.

At 20 guesses per second per graphics card:

| Attacker | Time to crack |
|---|---|
| One graphics card | 150 million years |
| 100 graphics cards | 1.5 million years |
| 1,000 graphics cards | 150,000 years |
| 10,000 graphics cards | 15,000 years |
| A nation with 100,000 graphics cards | 1,500 years |
| Every graphics card ever manufactured | Centuries |

Your password "mymomlovesme" survives all of them.

## Why length beats complexity

"J&38gF!" has 7 characters. Every symbol imaginable. Looks
strong. A graphics card with Argon2id cracks it in 4 months.

"mymomlovesme" has 12 characters. All lowercase. Looks weak.
The same graphics card needs 150 million years.

Length wins. Always. Every character you add multiplies the
combinations by 26. Five extra characters means 12 million
times harder. Special characters add nothing useful.

## What this means for you

Your backup password protects your data. Pick something long
that you can remember. A sentence. A joke. A lyric you will
never forget. Do not waste energy on symbols and capital
letters. Just make it long.

12 characters minimum. Aegis enforces this. Not because we
think you are careless. Because we calculated exactly how
much protection you need, and 12 is the line where no one
on Earth can touch you.

Not your ex. Not a hacker. Not a government. No one.
