.text
main:
  addi $t0, $zero, 5
  addi $t1, $zero, 7
  add  $t2, $t0, $t1     # 12
  add  $t3, $t2, $t0     # depends on previous ALU result (forwarding), expect 17

  addi $v0, $zero, 1     # print_int
  addu $a0, $t3, $zero
  syscall

  addi $v0, $zero, 10    # exit
  syscall
