.text
main:
  addi $sp, $sp, -4
  addi $t0, $zero, 21
  sw   $t0, 0($sp)

  lw   $t1, 0($sp)
  add  $t2, $t1, $t1     # immediate use of loaded value (load-use hazard), expect 42

  addi $v0, $zero, 1     # print_int
  addu $a0, $t2, $zero
  syscall

  addi $v0, $zero, 10    # exit
  syscall
