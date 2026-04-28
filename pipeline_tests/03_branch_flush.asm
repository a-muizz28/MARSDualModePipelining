.text
main:
  addi $t0, $zero, 1
  addi $t1, $zero, 1

  beq  $t0, $t1, target
  addi $t2, $zero, 99    # should be flushed on taken branch

target:
  addi $t2, $zero, 7

  addi $v0, $zero, 1     # print_int
  addu $a0, $t2, $zero
  syscall

  addi $v0, $zero, 10    # exit
  syscall
